package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.core.builder.mq.DeployMessageProducer;
import com.example.aicodebackend.core.builder.mq.DeployMessageProducer.PublishException;
import com.example.aicodebackend.core.builder.mq.DeployRabbitConfig;
import com.example.aicodebackend.core.builder.mq.DeployTaskMessage;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 部署构建队列（RabbitMQ 版）
 * 为什么需要它：部署一个 Vue 工程要跑 {@code npm install + npm run build}，单个任务会吃满一个 CPU 核
 * 并大量读写磁盘。改造前"每个请求起一个虚拟线程直接构建"，100 个应用同时部署就会拉起 100 个 npm 进程，CPU 100% 占满，磁盘 I/O 100% 占满，系统直接卡死。
 */
@Slf4j
@Component
public class DeployQueueManager {

    /**
     * 单次部署任务：应用 id、排队序号、任务 id、已重试次数与是否重投
     * 部署所需的一切（代码生成类型、deployKey、源码目录）都由消费端从数据库重新读取，
     * 避免任务对象持有过期快照。
     */
    public record DeployTask(Long appId, long sequence, String taskId, int attempt, boolean redelivered) {
    }

    /** 队列指标查询失败的返回值（区别于"队列为空"） */
    public static final int METRICS_UNAVAILABLE = -1;

    private final DeployMessageProducer producer;

    /**
     * AMQP 管理端：用来查队列水位
     * 这里注入的是接口 {@link AmqpAdmin} 而不是具体的 {@link RabbitAdmin}：
     * Spring Boot 3.5 自动配置声明的 {@code @Bean} 返回类型就是 {@code AmqpAdmin}
     */
    private final AmqpAdmin amqpAdmin;

    /** 每实例的构建并发（消费者 concurrency） */
    private final int workerCount;

    /** 队列容量上限（仅用于生成提示文案；真实上限由队列的 x-max-length 决定） */
    private final int maxQueueSize;

    /** 同步部署的构建额度：同步接口不能进 MQ 排队，拿到额度就构建，拿不到只在本地短暂等待 */
    private final Semaphore syncPermits;

    /** 同步构建额度总数（仅用于提示文案；与消费者并发 {@link #workerCount} 是两份额度） */
    private final int syncPermitCount;

    /**
     * 同步部署的等待者名额：最多允许这么多个请求短时间等待构建额度
     * 与 {@link #syncPermits} 分开计数：额度决定"同时在构建几个"，等待名额决定
     * "最多挂住几个请求线程"。没有这一层，高峰期每个同步请求都会阻塞在 {@code tryAcquire} 上……
     */
    private final Semaphore waiterSlots;

    /** 等待者名额上限（仅用于提示文案） */
    private final int syncMaxWaiters;

    /**
     * 同步部署等待构建额度的窗口（毫秒）
     * 构建要 1~3 分钟，等待窗口必须远小于构建耗时，否则等待者的请求线程会被长时间挂住。
     * 窗口只用来接住"刚好有额度释放"的情况，拿不到就让调用方改用异步接口。
     */
    private final long syncWaitTimeoutMillis;

    /**
     * 正在等待"同应用部署锁"的同步请求数
     * {@code /health/deploy-queue} 的 syncWaiting 需要把它算进去：只看 permit 等待者会漏掉
     * 正在等同应用构建结束（最多 1 秒）的那批请求。
     */
    private final AtomicInteger syncLockWaiters = new AtomicInteger();

    /** 本实例正在执行的任务数（同步构建 + 本实例消费的任务） */
    private final AtomicInteger localRunning = new AtomicInteger();

    /** 排队序号生成器（仅用于日志与前端提示，不参与幂等判断） */
    private final AtomicLong sequenceGenerator = new AtomicLong();

    /** 是否已进入关闭流程 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public DeployQueueManager(DeployMessageProducer producer,
                              AmqpAdmin amqpAdmin,
                              @Value("${deploy.queue.workers:4}") int workerCount,
                              @Value("${deploy.queue.max-queue-size:32}") int maxQueueSize,
                              @Value("${deploy.queue.sync-permits:1}") int syncPermits,
                              @Value("${deploy.queue.sync-max-waiters:10}") int syncMaxWaiters,
                              @Value("${deploy.queue.sync-wait-timeout-ms:5000}") long syncWaitTimeoutMillis) {
        this.producer = producer;
        this.amqpAdmin = amqpAdmin;
        this.workerCount = Math.max(1, workerCount);
        this.maxQueueSize = Math.max(1, maxQueueSize);
        int permitCount = Math.max(1, syncPermits);
        this.syncPermitCount = permitCount;
        // 公平模式：等待 permit 的请求按排队顺序获得额度，避免快路径一直插队导致等待者饿死
        this.syncPermits = new Semaphore(permitCount, true);
        this.syncMaxWaiters = Math.max(1, syncMaxWaiters);
        this.waiterSlots = new Semaphore(this.syncMaxWaiters);
        this.syncWaitTimeoutMillis = Math.max(0, syncWaitTimeoutMillis);
        log.info("部署构建队列初始化完成：RabbitMQ 队列={}, 每实例并发 worker={}, 队列容量={}, "
                        + "同步部署额度={}, 同步排队上限={}, 同步等待窗口={}ms",
                DeployRabbitConfig.QUEUE, this.workerCount, this.maxQueueSize,
                this.syncPermitCount, this.syncMaxWaiters, this.syncWaitTimeoutMillis);
    }

    /** 提交一个部署任务（自动生成任务 id，供测试/补跑使用） */
    public long submit(Long appId, Long operatorId) {
        return submit(appId, operatorId, UUID.randomUUID().toString());
    }

    /**
     * 提交一个部署任务
     * 消息发布后会等待 broker 确认：确认失败（队列已满 / 服务不可用）直接抛业务异常，
     * 由调用方把应用状态回滚，避免用户卡在"排队中"……
     */
    public long submit(Long appId, Long operatorId, String taskId) {
        if (shuttingDown.get()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "服务正在关闭，暂不接收新的部署任务");
        }
        long sequence = sequenceGenerator.incrementAndGet();
        DeployTaskMessage message = DeployTaskMessage.of(appId, sequence, operatorId, taskId);
        try {
            producer.publish(message);
        } catch (RuntimeException e) {
            if (e instanceof PublishException publishException && publishException.isOutcomeUnknown()) {
                log.warn("部署任务发布结果未知（broker 确认超时），appId: {}, sequence: {}, taskId: {}",
                        appId, sequence, taskId);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "部署任务提交确认超时，消息可能已入队；请稍后刷新部署状态，若仍为失败可直接重新提交");
            }
            // 队列满属于预期内的背压（429），broker 不可用才是系统故障（500）：
            // 两者都提示"稍后重试"会让前端无法区分，运维也看不出是容量问题还是基础设施问题
            if (isQueueFull(e)) {
                log.warn("部署队列已满，拒绝入队，appId: {}, sequence: {}, 容量: {}",
                        appId, sequence, maxQueueSize);
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, queueFullMessage(e));
            }
            log.error("部署任务入队失败，appId: {}, sequence: {}", appId, sequence, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, queueFullMessage(e));
        }
        // 这里不再同步查 broker 水位：提交是请求线程上的热路径，管理端 RPC 会白加一次延迟；
        // 真实水位从 /health/deploy-queue 或巡检日志看
        log.info("部署任务入队成功，appId: {}, sequence: {}, taskId: {}", appId, sequence, taskId);
        return sequence;
    }

    /**
     * 以"限流"的方式执行一段构建逻辑（供同步部署接口使用），等待窗口取配置
     * 语义：有空额度立刻执行；没有额度时最多占用一个等待名额并等
     * {@code deploy.queue.sync-wait-timeout-ms}；等不到（或等待名额已满）当场失败……
     */
    public <T> T runWithPermit(Callable<T> action) throws InterruptedException {
        return runWithPermit(action, Duration.ofMillis(syncWaitTimeoutMillis));
    }

    /**
     * 以"限流"的方式执行一段构建逻辑（可指定等待窗口）
     * 两层准入，避免"排队"变成线程池杀手：
     * 无论是否等到额度都会立刻归还等待者名额，因此名额只代表"正在等待的请求数"，不代表在构建。
     */
    public <T> T runWithPermit(Callable<T> action, Duration timeout) throws InterruptedException {
        if (shuttingDown.get()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "服务正在关闭，暂不接收新的部署任务");
        }
        acquireSyncPermit(Math.max(0, timeout.toMillis()));
        localRunning.incrementAndGet();
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署执行失败：" + e.getMessage());
        } finally {
            localRunning.decrementAndGet();
            syncPermits.release();
        }
    }

    /**
     * 获取同步部署的构建额度（快失败 + 有界排队）
     * 先无等待地抢一次额度；抢不到才有资格进入等待队列。等待名额用 {@link Semaphore} 而不是计数器
     * 是为了让"占用 + 归还"在所有异常路径（包括 {@link InterruptedException}）下都由 finally 兜住。
     */
    private void acquireSyncPermit(long timeoutMillis) throws InterruptedException {

        //    一旦有人在排队，就不再走快路径（否则后来者会一直插队，公平信号量也救不了等待者）。
        if (waiterSlots.availablePermits() == syncMaxWaiters && syncPermits.tryAcquire()) {
            return;
        }

        if (!waiterSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "当前同步部署请求较多（已有 " + syncMaxWaiters + " 个请求在排队等待构建额度），"
                            + "请改用异步部署接口 /app/deploy/async");
        }
        try {

            if (!syncPermits.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
                // 这里要说的是"同步额度"而不是消费者并发（workerCount）：
                // 挡住同步请求的是 sync-permits，说成 workers 会让用户误以为还有 3 个构建位
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "当前部署任务较多（同步构建额度 " + syncPermitCount + " 个已全部占用），"
                                + "请改用异步部署接口 /app/deploy/async");
            }
        } finally {
            // 拿到额度（或超时/中断）后立刻退出等待队列，名额只覆盖"等待"这段时间
            waiterSlots.release();
        }
    }

    /**
     * 当前正在等待同步部署资源的请求数（等待构建额度 + 等待同应用部署锁）
     * 只统计本实例；用于观测同步接口的背压。等待 permit 的部分达到
     * {@code deploy.queue.sync-max-waiters} 后新请求会被直接拒绝；等待应用锁的请求最多 1 秒。
     */
    public int syncWaitingCount() {
        int permitWaiters = syncMaxWaiters - waiterSlots.availablePermits();
        return permitWaiters + Math.max(0, syncLockWaiters.get());
    }

    /** 标记"一个同步请求开始等待同应用部署锁" */
    public void syncLockWaitStarted() {
        syncLockWaiters.incrementAndGet();
    }

    /** 标记"一个同步请求结束等待同应用部署锁" */
    public void syncLockWaitFinished() {
        syncLockWaiters.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /** 队列中等待处理的消息数（broker 侧真实值） */
    public int queueSize() {
        QueueInformation info = queueInfo();
        if (info == null) {
            return METRICS_UNAVAILABLE;
        }
        long count = info.getMessageCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** 当前订阅该队列的消费者数量（多实例部署时能看到全局消费者总数） */
    public int consumerCount() {
        QueueInformation info = queueInfo();
        if (info == null) {
            return METRICS_UNAVAILABLE;
        }
        long count = info.getConsumerCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * 死信队列中积压的消息数
     * 死信代表"重试耗尽仍失败"的任务，正常情况下应该长期为 0；一旦有值，
     * 说明有部署任务需要人工排查（见 DOCKER.md 第 7.1 节）。
     */
    public int deadLetterCount() {
        QueueInformation info = queueInfo(DeployRabbitConfig.DEAD_QUEUE);
        if (info == null) {
            return METRICS_UNAVAILABLE;
        }
        long count = info.getMessageCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** 本实例正在构建的任务数（同步部署 + 本实例消费的任务） */
    public int executingCount() {
        return localRunning.get();
    }

    /**
     * 在途任务数（等待队列 + 本实例执行中）
     * 这是背压的真实水位：等待部分来自 broker，执行部分只统计本实例（跨实例的执行数需要管理端 API，
     * 这里不做过度设计）。
     */
    public int inFlightCount() {
        int waiting = queueSize();
        if (waiting == METRICS_UNAVAILABLE) {
            return METRICS_UNAVAILABLE;
        }
        return waiting + executingCount();
    }

    /** 标记"本实例开始执行一个任务"（由消费者调用，用于水位统计） */
    public void taskStarted() {
        localRunning.incrementAndGet();
    }

    /** 标记"本实例结束一个任务" */
    public void taskFinished() {
        localRunning.decrementAndGet();
    }

    /** 每实例的构建并发数 */
    public int getWorkerCount() {
        return workerCount;
    }

    /** 同步部署的构建额度总数（每实例） */
    public int getSyncPermitCount() {
        return syncPermitCount;
    }

    /** 队列容量上限 */
    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    /**
     * 应用关闭：停止接收新任务
     * 排队中的消息留在 broker 里，随下次启动继续被消费（这正是 MQ 相对内存队列的价值），
     * 因此这里只置位开关并打日志，不做"清空队列"的操作。
     */
    @PreDestroy
    public void shutdown() {
        log.info("部署构建队列关闭中，本实例执行中: {}，broker 侧等待: {}", executingCount(), queueSize());
        shuttingDown.set(true);
    }

    /** 查询业务队列信息 */
    private QueueInformation queueInfo() {
        return queueInfo(DeployRabbitConfig.QUEUE);
    }

    /** 查询指定队列的信息 */
    private QueueInformation queueInfo(String queueName) {
        if (!(amqpAdmin instanceof RabbitAdmin rabbitAdmin)) {
            log.debug("当前 AmqpAdmin 实现不是 RabbitAdmin，跳过队列水位查询：{}", amqpAdmin.getClass().getName());
            return null;
        }
        try {
            return rabbitAdmin.getQueueInfo(queueName);
        } catch (Exception e) {
            log.debug("查询队列水位失败（broker 暂不可用），queue={}: {}", queueName, e.getMessage());
            return null;
        }
    }

    /**
     * 入队失败时的用户提示
     * 队列满和 broker 不可用对用户来说都表现为"现在提交不上去"，但处置方式不同：
     * 前者稍后重试即可，后者说明基础设施异常。这里按异常信息区分，避免把运维问题说成"请稍后重试"。
     */
    private String queueFullMessage(RuntimeException error) {
        if (isQueueFull(error)) {
            return "部署队列已满（最多排队 " + maxQueueSize + " 个，同时构建 " + workerCount + " 个），请稍后再试";
        }
        return "部署任务入队失败（消息队列暂不可用），请稍后重试";
    }

    /**
     * 判断入队失败是否属于"队列已满"（正常背压）
     * 只认队列容量溢出的明确文案。{@code PRECONDITION} 还可能是"队列声明参数不一致"
     * 之类的配置错误，不能当成"忙"，否则会误导用户和运维。
     */
    private boolean isQueueFull(RuntimeException error) {
        String raw = error.getMessage() == null ? "" : error.getMessage();
        return raw.contains("queue length limit");
    }
}
