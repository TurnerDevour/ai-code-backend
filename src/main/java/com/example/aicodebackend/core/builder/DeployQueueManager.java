package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 部署构建队列（限流）
 * <p>
 * 为什么需要它：部署一个 Vue 工程要跑 {@code npm install + npm run build}，单个任务会吃满一个 CPU 核并大量读写磁盘。
 * 之前的实现是"每个请求起一个虚拟线程直接构建"，100 个应用同时部署就会拉起 100 个 npm 进程，
 * 实测结果是大量 {@code npm install 执行失败}、部署成功率掉到 46%。
 * <p>
 * 现在的模型是<b>有界等待队列 + 固定构建额度</b>：
 * <ul>
 *     <li>提交的任务进入容量 {@code deploy.queue.max-queue-size}（默认 32）的等待队列，状态为 {@code queued}；
 *     队列满时立即拒绝，并给出明确提示；</li>
 *     <li>同时最多 {@code deploy.queue.workers} 个任务真正构建（默认 4），由 {@link Semaphore} 严格控制——
 *     拿不到额度的消费者线程会挂起，不会去抢活；</li>
 *     <li>消费者线程池设置成"不拒绝提交"（{@code SynchronousQueue} + 无上限线程）：
 *     线程本身几乎不耗资源（大部分时间阻塞在额度上），真正的资源闸门是构建额度。
 *     这样避免"线程池队列满导致任务没人消费"的漏执行问题（实测曾有一个应用永远停在 queued）；</li>
 *     <li>任务异常只记录并交出额度，不影响后续任务；应用关闭时停止接收并中断构建。</li>
 * </ul>
 * 注意：这是<b>进程内</b>队列。多实例部署时每个实例各有一份队列，总构建并发 = 实例数 × workers；
 * 需要全局限流应换成 Redis 队列 / MQ + 统一 worker 集群。
 */
@Slf4j
@Component
public class DeployQueueManager {

    /**
     * 单次部署任务：只带应用 id 和排队序号（部署所需的一切都从数据库读取，避免任务对象持有过期快照）
     */
    public record DeployTask(Long appId, long sequence) {
    }

    /**
     * 任务执行的抽象：实际构建逻辑由 AppServiceImpl 提供，避免 builder 包反向依赖 service 包
     */
    @FunctionalInterface
    public interface DeployTaskHandler {
        /**
         * 执行一次部署
         *
         * @param task 任务
         */
        void handle(DeployTask task);
    }

    /**
     * 等待执行的部署任务
     */
    private final BlockingQueue<DeployTask> waiting;

    /**
     * 消费者线程池：不拒绝提交，真正的并发闸门是 {@link #buildPermits}
     */
    private final ThreadPoolExecutor consumers;

    /**
     * 构建额度：最多同时 workerCount 个任务在执行 handler
     */
    private final Semaphore buildPermits;

    /**
     * 正在执行 handler 的任务（用于统计并发数与排队位置）
     */
    private final ConcurrentLinkedDeque<DeployTask> executing = new ConcurrentLinkedDeque<>();

    /**
     * 已排队/执行中的任务：appId -> 排队序号
     */
    private final ConcurrentHashMap<Long, Long> activeTasks = new ConcurrentHashMap<>();

    /**
     * 在途任务数（等待队列 + 已取到但还在等构建额度）
     * <p>
     * 用它做背压：上限 = 同时构建数 + 队列容量。消费线程可能"取到任务先阻塞在额度上"，
     * 只看等待队列长度会在高并发提交时失真，因此用显式计数保证"队列满即拒绝"是确定性的。
     */
    private final AtomicInteger inFlightTasks = new AtomicInteger();

    /**
     * 任务的执行器：appId -> handler（消费线程按 appId 取用，任务不绑死在提交线程上）
     */
    private final ConcurrentHashMap<Long, DeployTaskHandler> taskHandlers = new ConcurrentHashMap<>();

    /**
     * 排队序号生成器
     */
    private final AtomicLong sequenceGenerator = new AtomicLong();

    /**
     * 是否已进入关闭流程
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private final int workerCount;

    private final int maxQueueSize;

    public DeployQueueManager(@Value("${deploy.queue.workers:4}") int workerCount,
                              @Value("${deploy.queue.max-queue-size:32}") int maxQueueSize) {
        this.workerCount = Math.max(1, workerCount);
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.waiting = new ArrayBlockingQueue<>(this.maxQueueSize);
        this.buildPermits = new Semaphore(this.workerCount);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "deploy-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.consumers = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        log.info("部署构建队列初始化完成：并发 worker={}, 队列容量={}", this.workerCount, this.maxQueueSize);
    }

    /**
     * 提交一个部署任务
     * <p>
     * 同一个应用重复提交会被忽略（返回已有序号）；队列满时抛业务异常。
     *
     * @param appId   应用ID
     * @param handler 构建执行器
     *
     * @return 排队序号
     */
    public long submit(Long appId, DeployTaskHandler handler) {
        if (shuttingDown.get()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "服务正在关闭，暂不接收新的部署任务");
        }
        Long existing = activeTasks.get(appId);
        if (existing != null) {
            log.info("应用已在部署队列中，忽略重复提交，appId: {}, sequence: {}", appId, existing);
            return existing;
        }
        long sequence = sequenceGenerator.incrementAndGet();
        DeployTask task = new DeployTask(appId, sequence);
        // 背压：在途任务（含正在等额度的）超过"并发数 + 队列容量"时直接拒绝
        if (inFlightTasks.incrementAndGet() > workerCount + maxQueueSize) {
            inFlightTasks.decrementAndGet();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "部署队列已满（最多排队 " + maxQueueSize + " 个，同时构建 " + workerCount + " 个），请稍后再试");
        }
        activeTasks.put(appId, sequence);
        taskHandlers.put(appId, handler);
        if (!waiting.offer(task)) {
            activeTasks.remove(appId, sequence);
            taskHandlers.remove(appId, handler);
            inFlightTasks.decrementAndGet();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "部署队列已满（最多排队 " + maxQueueSize + " 个，同时构建 " + workerCount + " 个），请稍后再试");
        }
        consumers.execute(this::consume);
        log.info("部署任务入队，appId: {}, sequence: {}, 等待队列长度: {}, 在途: {}",
                appId, sequence, waiting.size(), inFlightTasks.get());
        return sequence;
    }

    /**
     * 消费循环：反复从等待队列取任务，取到后必须拿到构建额度才执行
     * <p>
     * 队列取空即退出（本次提交已经有一个消费者，后续提交会再拉起新的消费者），
     * 即使"取到任务却没拿到额度"，额度被释放后也会被再次唤醒继续处理。
     */
    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            DeployTask task = waiting.poll();
            if (task == null) {
                return;
            }
            DeployTaskHandler handler = taskHandlers.get(task.appId());
            if (handler == null) {
                log.warn("找不到任务的执行器，丢弃任务，appId: {}, sequence: {}", task.appId(), task.sequence());
                activeTasks.remove(task.appId(), task.sequence());
                inFlightTasks.decrementAndGet();
                continue;
            }
            boolean acquired = false;
            try {
                buildPermits.acquire();
                acquired = true;
                executing.add(task);
                handler.handle(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                // handler 内部负责把失败落库，这里兜底保证消费者不被打断
                log.error("部署任务执行异常，appId: {}, sequence: {}", task.appId(), task.sequence(), e);
            } finally {
                executing.remove(task);
                activeTasks.remove(task.appId(), task.sequence());
                taskHandlers.remove(task.appId(), handler);
                if (acquired) {
                    buildPermits.release();
                }
                inFlightTasks.decrementAndGet();
            }
        }
    }

    /**
     * 以"限流"的方式执行一段构建逻辑（供同步部署接口使用）
     * <p>
     * 同步接口需要当场返回部署地址，不能走"排队 + 轮询"，但同样必须受构建并发限制：
     * 这里直接申请一个构建额度，拿到才执行，超时则明确失败，而不是无限制地起 npm 进程。
     *
     * @param action   构建动作（返回值即部署地址）
     * @param timeout  等待额度的最长时间
     * @param <T>      返回值类型
     *
     * @return 构建动作的返回值
     *
     * @throws InterruptedException 等待被中断
     */
    public <T> T runWithPermit(java.util.concurrent.Callable<T> action, java.time.Duration timeout) throws InterruptedException {
        boolean acquired = buildPermits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "当前部署任务较多（同时构建 " + workerCount + " 个），请稍后重试或改用异步部署接口");
        }
        // 计入 executing 统计，保证同步/异步两种部署方式在水位接口里都能被观测到
        DeployTask marker = new DeployTask(-1L, sequenceGenerator.incrementAndGet());
        executing.add(marker);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署执行失败：" + e.getMessage());
        } finally {
            executing.remove(marker);
            buildPermits.release();
        }
    }

    /**
     * 查询排队位置（从 1 开始）
     * <p>
     * 正在构建（已拿到额度）返回 null；在等待队列中返回它在队列里的位置（1 表示下一个被调度）。
     *
     * @param appId 应用ID
     *
     * @return 排队位置，不在排队时返回 null
     */
    public Integer queuePosition(Long appId) {
        Long sequence = activeTasks.get(appId);
        if (sequence == null) {
            return null;
        }
        for (DeployTask task : executing) {
            if (task.appId().equals(appId)) {
                return null;
            }
        }
        int position = 1;
        for (DeployTask task : waiting) {
            if (task.sequence() < sequence) {
                position++;
            }
        }
        return position;
    }

    /**
     * 当前等待构建的任务数
     *
     * @return 队列长度
     */
    public int queueSize() {
        return waiting.size();
    }

    /**
     * 正在构建的任务数
     *
     * @return 执行中的任务数
     */
    public int executingCount() {
        return executing.size();
    }

    /**
     * 在途任务数（等待队列 + 已取到但还在等构建额度）
     * <p>
     * 这是背压的真实水位：上限 = 并发构建数 + 队列容量。
     *
     * @return 在途任务数
     */
    public int inFlightCount() {
        return inFlightTasks.get();
    }

    /**
     * 构建并发数
     *
     * @return worker 数量
     */
    public int getWorkerCount() {
        return workerCount;
    }

    /**
     * 队列容量
     *
     * @return 最大排队数
     */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /**
     * 应用关闭：停止接收新任务并中断正在执行的构建
     */
    @PreDestroy
    public void shutdown() {
        log.info("部署构建队列关闭中，剩余排队任务: {}，执行中: {}", queueSize(), executingCount());
        shuttingDown.set(true);
        consumers.shutdownNow();
        try {
            if (!consumers.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("部署消费者线程未在 10 秒内退出，已强制关闭");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
