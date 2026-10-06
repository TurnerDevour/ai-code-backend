package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署构建队列（限流）的回归测试
 * <p>
 * 背景：之前"每个请求一个线程直接构建"，100 个应用同时部署会拉起 100 个 npm 进程，
 * 实测部署成功率掉到 46%（大量 npm install 失败）。改为固定 worker + 有界队列后，
 * 必须保证：并发构建数不超过配置值、队列满时明确拒绝、worker 崩溃不影响后续任务。
 */
class DeployQueueManagerTest {

    @Test
    void shouldLimitConcurrentBuildsToWorkerCount() throws Exception {
        int workers = 2;
        int tasks = 6;
        DeployQueueManager manager = new DeployQueueManager(workers, 16);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(tasks);
        int[] order = new int[tasks];
        AtomicInteger orderCursor = new AtomicInteger();
        try {
            for (int i = 0; i < tasks; i++) {
                final int index = i;
                manager.submit((long) index, task -> {
                    int now = running.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    order[orderCursor.getAndIncrement()] = index;
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        running.decrementAndGet();
                        allDone.countDown();
                    }
                });
            }
            assertTrue(allDone.await(20, TimeUnit.SECONDS), "部署任务未在超时时间内全部完成");
            assertTrue(peak.get() <= workers, "并发构建数超过了 worker 上限: peak=" + peak.get() + ", workers=" + workers);
            assertTrue(peak.get() >= 1, "任务完全没有执行");
            // 任务必须全部执行且只执行一次（执行先后顺序受额度获取顺序影响，不做 FIFO 断言）
            int[] counts = new int[tasks];
            for (int index : order) {
                counts[index]++;
            }
            for (int i = 0; i < tasks; i++) {
                assertEquals(1, counts[i], "任务 " + i + " 的执行次数不是 1: " + java.util.Arrays.toString(order));
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shouldRejectWhenQueueIsFull() throws Exception {
        int workers = 2;
        int queueSize = 2;
        DeployQueueManager manager = new DeployQueueManager(workers, queueSize);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try {
            // 用 workers 个"长任务"占满构建额度（它们会阻塞在 handler 里直到 release）
            for (int i = 0; i < workers; i++) {
                manager.submit((long) i, task -> {
                    started.incrementAndGet();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            waitUntil(() -> started.get() == workers, 10_000, "占位任务未全部开始执行");
            // 填满等待队列
            for (int i = 0; i < queueSize; i++) {
                manager.submit((long) (100 + i), task -> {
                });
            }
            waitUntil(() -> manager.inFlightCount() >= workers + queueSize, 10_000,
                    "在途任务数未达到上限（并发+队列），实际: " + manager.inFlightCount());
            // 再提交应被拒绝，且给出明确提示
            BusinessException ex = assertThrows(BusinessException.class, () -> manager.submit(999L, task -> {
            }));
            assertTrue(ex.getMessage().contains("队列已满"), "队列满时应返回明确提示，实际: " + ex.getMessage());
            assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
            // 被拒绝的应用不应残留在队列索引里
            assertNull(manager.queuePosition(999L), "被拒绝的任务不应出现在排队中");
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    @Test
    void shouldIgnoreDuplicateSubmissionOfSameApp() throws Exception {
        DeployQueueManager manager = new DeployQueueManager(1, 8);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            manager.submit(100L, task -> {
                executions.incrementAndGet();
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(10, TimeUnit.SECONDS), "worker 未开始执行");
            long first = manager.submit(100L, task -> executions.incrementAndGet());
            long second = manager.submit(100L, task -> executions.incrementAndGet());
            assertEquals(first, second, "同一个应用重复提交应复用同一排队序号");
            assertEquals(1, executions.get(), "同一个应用不应被执行多次");
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    @Test
    void workerShouldSurviveTaskFailure() throws Exception {
        DeployQueueManager manager = new DeployQueueManager(1, 8);
        CountDownLatch allDone = new CountDownLatch(2);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            // 第一个任务抛异常，不应影响第二个任务
            manager.submit(1L, task -> {
                try {
                    throw new IllegalStateException("模拟构建崩溃");
                } finally {
                    allDone.countDown();
                }
            });
            manager.submit(2L, task -> {
                try {
                    succeeded.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
            assertTrue(allDone.await(20, TimeUnit.SECONDS), "任务未全部执行（worker 可能被异常打断）");
            assertEquals(1, succeeded.get(), "异常任务之后的正常任务没有被执行");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shouldStopAcceptingAfterShutdown() {
        DeployQueueManager manager = new DeployQueueManager(1, 4);
        manager.shutdown();
        assertThrows(Exception.class, () -> manager.submit(1L, task -> {
        }), "关闭后不应再接收新任务");
    }

    /**
     * 轮询等待条件成立（避免依赖固定 sleep 造成偶发失败）
     *
     * @param condition 条件
     * @param timeoutMs 超时时间
     * @param message   失败提示
     */
    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
