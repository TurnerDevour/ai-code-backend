package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.core.builder.mq.DeployMessageProducer;
import com.example.aicodebackend.core.builder.mq.DeployTaskMessage;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部署构建队列（RabbitMQ 版）的单元测试
 * 改造后"排队"和"并发"由 broker 与消费者容器负责，进程内只剩下三件事需要回归……
 */
class DeployQueueManagerTest {

    private static final int WORKERS = 2;

    private static final int MAX_QUEUE_SIZE = 16;

    /** 同步部署默认允许的等待者数量（与生产默认值一致） */
    private static final int MAX_WAITERS = 10;

    /** 同步部署默认等待窗口（与生产默认值一致） */
    private static final long SYNC_WAIT_TIMEOUT_MS = 5_000;

    /** 构造一个"broker 正常"的管理器（同步额度 1 个） */
    private static DeployQueueManager newManager(DeployMessageProducer producer, RabbitAdmin rabbitAdmin) {
        return newManager(producer, rabbitAdmin, 1, MAX_WAITERS, SYNC_WAIT_TIMEOUT_MS);
    }

    /** 按指定同步额度/等待名额/等待窗口构造管理器 */
    private static DeployQueueManager newManager(DeployMessageProducer producer, RabbitAdmin rabbitAdmin,
                                                 int syncPermits, int maxWaiters, long waitTimeoutMs) {
        return new DeployQueueManager(producer, rabbitAdmin, WORKERS, MAX_QUEUE_SIZE,
                syncPermits, maxWaiters, waitTimeoutMs);
    }

    /** 等同步等待者数量达到期望值（避免用 sleep 猜时序） */
    private static void awaitWaitingCount(DeployQueueManager manager, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (manager.syncWaitingCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, manager.syncWaitingCount(), "等待者名额占用情况与预期不符");
    }

    @Test
    @DisplayName("提交部署：消息发到队列，并返回递增的排队序号")
    void shouldPublishMessageAndReturnSequence() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        when(rabbitAdmin.getQueueInfo(any())).thenReturn(new QueueInformation("deploy.queue", 0, WORKERS));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        long first = manager.submit(1001L, 2002L);
        long second = manager.submit(1003L, 2002L);

        ArgumentCaptor<DeployTaskMessage> captor = ArgumentCaptor.forClass(DeployTaskMessage.class);
        verify(producer, org.mockito.Mockito.times(2)).publish(captor.capture());
        DeployTaskMessage message = captor.getAllValues().get(0);
        assertEquals(1001L, message.appId(), "消息里必须带应用 id");
        assertEquals(2002L, message.operatorId(), "消息里必须带发起人，便于追溯");
        assertEquals(first, message.sequence(), "返回的序号要与消息一致");
        assertTrue(second > first, "序号必须递增");
    }

    @Test
    @DisplayName("队列满：broker 拒绝后要给出\"队列已满\"的业务提示")
    void shouldTranslateBrokerRejectionIntoQueueFull() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        doThrow(new DeployMessageProducer.PublishException("broker 拒绝接收部署任务：queue length limit exceeded"))
                .when(producer).publish(any(DeployTaskMessage.class));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        BusinessException error = assertThrows(BusinessException.class, () -> manager.submit(1L, 2L));
        assertTrue(error.getMessage().contains("队列已满"), "提示里要说清是队列满，实际=" + error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(MAX_QUEUE_SIZE)), "提示里要带上容量");
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), error.getCode(),
                "队列满是预期内的背压，应返回 42900 而不是系统错误");
    }

    @Test
    @DisplayName("broker 不可用：提示不能糊弄成\"队列满\"")
    void shouldDistinguishBrokerFailureFromQueueFull() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        doThrow(new DeployMessageProducer.PublishException("发布部署任务消息失败：connection refused",
                new AmqpConnectException(new RuntimeException("connection refused"))))
                .when(producer).publish(any(DeployTaskMessage.class));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        BusinessException error = assertThrows(BusinessException.class, () -> manager.submit(1L, 2L));
        assertTrue(error.getMessage().contains("消息队列暂不可用"), "基础设施异常要单独提示，实际=" + error.getMessage());
    }

    @Test
    @DisplayName("水位来自 broker：等待数/消费者数取队列信息")
    void shouldReadMetricsFromBroker() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        when(rabbitAdmin.getQueueInfo(eq("deploy.queue")))
                .thenReturn(new QueueInformation("deploy.queue", 5, 3));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        manager.taskStarted();
        assertEquals(5, manager.queueSize());
        assertEquals(3, manager.consumerCount());
        assertEquals(1, manager.executingCount());
        assertEquals(6, manager.inFlightCount(), "在途 = 等待 + 本实例执行中");
    }

    @Test
    @DisplayName("broker 查询失败：水位返回 -1，不能假装队列为空")
    void shouldReportMetricsUnavailableWhenBrokerFails() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        when(rabbitAdmin.getQueueInfo(any())).thenThrow(new AmqpConnectException(new RuntimeException("down")));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        assertEquals(DeployQueueManager.METRICS_UNAVAILABLE, manager.queueSize());
        assertEquals(DeployQueueManager.METRICS_UNAVAILABLE, manager.consumerCount());
        assertEquals(DeployQueueManager.METRICS_UNAVAILABLE, manager.inFlightCount());
    }

    @Test
    @DisplayName("同步部署：没有额度时只等一个短窗口，窗口耗尽当场失败并提示异步接口")
    void shouldFailAfterWaitWindowExpires() throws Exception {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = startPermitHolder(manager, holding, release, holderError);

        try {
            // 额度只有 1 个：第二个请求进入有界排队，等不到就失败，而不是挂 5 分钟
            BusinessException error = assertThrows(BusinessException.class,
                    () -> manager.runWithPermit(() -> "blocked", Duration.ofMillis(200)));
            assertTrue(error.getMessage().contains("当前部署任务较多"),
                    "提示要说明是构建额度不足，实际=" + error.getMessage());
            assertTrue(error.getMessage().contains("/app/deploy/async"),
                    "提示要给出可执行的替代方案，实际=" + error.getMessage());
            assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), error.getCode(),
                    "同步构建额度耗尽应返回 42900，前端才能与内部错误区分开");
        } finally {
            release.countDown();
            holder.join(5_000);
        }
        assertTrue(holderError.get() == null, "持有额度的线程不应报错：" + holderError.get());
        assertEquals(0, manager.syncWaitingCount(), "超时后等待名额必须归还");
    }

    @Test
    @DisplayName("同步部署：等待窗口取配置项，不再写死 300 秒")
    void shouldUseConfiguredShortWaitWindow() throws Exception {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        // 等待窗口 100ms：若仍按旧常量 300s 等待，这个用例会明显超时
        DeployQueueManager manager = newManager(producer, rabbitAdmin, 1, MAX_WAITERS, 100);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = startPermitHolder(manager, holding, release, holderError);

        long start = System.nanoTime();
        try {
            assertThrows(BusinessException.class, () -> manager.runWithPermit(() -> "blocked"));
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            release.countDown();
            holder.join(5_000);
            assertTrue(elapsedMs < 2_000, "等待窗口应取配置的 100ms，实际等待 " + elapsedMs + "ms");
        }
        assertTrue(holderError.get() == null, "持有额度的线程不应报错：" + holderError.get());
    }

    @Test
    @DisplayName("同步部署：等待名额已满时后来的请求直接拒绝，不再挂住线程")
    void shouldRejectWhenWaiterSlotsExhausted() throws Exception {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        // 构建额度 1 个、最多 1 个等待者：再来的请求没有排队资格
        DeployQueueManager manager = newManager(producer, rabbitAdmin, 1, 1, 10_000);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = startPermitHolder(manager, holding, release, holderError);

        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicReference<Throwable> waiterError = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                manager.runWithPermit(() -> "queued", Duration.ofSeconds(10));
            } catch (Throwable e) {
                waiterError.set(e);
            } finally {
                waiterDone.countDown();
            }
        }, "sync-deploy-waiter");
        waiter.start();

        try {
            awaitWaitingCount(manager, 1);
            // 等待名额被占满后，第三个请求必须立刻失败（不能进入等待）
            BusinessException error = assertThrows(BusinessException.class,
                    () -> manager.runWithPermit(() -> "rejected", Duration.ZERO));
            assertTrue(error.getMessage().contains("在排队等待构建额度"),
                    "提示要说明排队人数已满，实际=" + error.getMessage());
            assertTrue(error.getMessage().contains("/app/deploy/async"),
                    "提示要给出可执行的替代方案，实际=" + error.getMessage());
        } finally {
            release.countDown();
            holder.join(5_000);
            assertTrue(waiterDone.await(5, TimeUnit.SECONDS), "等待者应在额度归还后拿到额度");
        }
        assertTrue(waiterError.get() == null, "等待者不应失败：" + waiterError.get());
        assertEquals(0, manager.syncWaitingCount(), "等待结束后名额必须归还");
    }

    @Test
    @DisplayName("同步部署：等待超时后归还排队名额，后续请求不会被误判为「排队已满」")
    void shouldReleaseWaiterSlotAfterTimeout() throws Exception {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        DeployQueueManager manager = newManager(producer, rabbitAdmin, 1, 1, 100);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderError = new AtomicReference<>();
        Thread holder = startPermitHolder(manager, holding, release, holderError);

        try {
            // 第一次排队超时：说明它确实占用过唯一的等待名额
            BusinessException first = assertThrows(BusinessException.class,
                    () -> manager.runWithPermit(() -> "blocked", Duration.ofMillis(50)));
            assertTrue(first.getMessage().contains("当前部署任务较多"),
                    "首次失败应是等待窗口耗尽，实际=" + first.getMessage());
            assertEquals(0, manager.syncWaitingCount(), "超时后必须归还等待名额");

            // 第二次仍应能排队（而不是被"排队已满"拦下），证明名额已释放
            BusinessException second = assertThrows(BusinessException.class,
                    () -> manager.runWithPermit(() -> "blocked-again", Duration.ofMillis(50)));
            assertTrue(second.getMessage().contains("当前部署任务较多"),
                    "第二次失败也应是等待窗口耗尽，实际=" + second.getMessage());
        } finally {
            release.countDown();
            holder.join(5_000);
        }
        assertTrue(holderError.get() == null, "持有额度的线程不应报错：" + holderError.get());
    }

    @Test
    @DisplayName("同步部署：拿到额度会执行构建，并在结束后归还额度")
    void shouldReleaseSyncPermitAfterBuild() throws Exception {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        String result = manager.runWithPermit(() -> "https://wlbc.top/dist/abc123/", Duration.ofSeconds(1));

        assertNotNull(result);
        assertEquals(0, manager.executingCount(), "构建结束后执行计数必须归零");
        // 额度已归还：紧接着还能再拿到一次
        assertEquals("second", manager.runWithPermit(() -> "second", Duration.ofMillis(200)));
    }

    /** 启动一个"占住唯一构建额度"的线程，直到测试释放 */
    private static Thread startPermitHolder(DeployQueueManager manager, CountDownLatch holding,
                                            CountDownLatch release, AtomicReference<Throwable> holderError)
            throws InterruptedException {
        Thread holder = new Thread(() -> {
            try {
                manager.runWithPermit(() -> {
                    holding.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "ok";
                }, Duration.ofSeconds(2));
            } catch (Throwable e) {
                holderError.set(e);
            }
        }, "sync-deploy-holder");
        holder.start();
        assertTrue(holding.await(3, TimeUnit.SECONDS), "持有额度的线程应已启动");
        return holder;
    }

    @Test
    @DisplayName("关闭后不再接收新任务，且不会去清空 broker 里的排队消息")
    void shouldRejectNewTasksAfterShutdown() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        when(rabbitAdmin.getQueueInfo(any())).thenReturn(new QueueInformation("deploy.queue", 0, 0));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        manager.shutdown();

        BusinessException error = assertThrows(BusinessException.class, () -> manager.submit(1L, 2L));
        assertTrue(error.getMessage().contains("正在关闭"));
        // 关键：关闭时不应该做"清空队列"之类的动作（排队任务要留到下次启动继续消费）
        verify(producer, never()).publish(any(DeployTaskMessage.class));
    }

    @Test
    @DisplayName("关闭后同步部署也不再启动新构建")
    void shouldRejectSyncDeployAfterShutdown() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        manager.shutdown();

        BusinessException error = assertThrows(BusinessException.class,
                () -> manager.runWithPermit(() -> "should-not-run", Duration.ofMillis(100)));
        assertTrue(error.getMessage().contains("正在关闭"));
        assertEquals(0, manager.executingCount(), "关闭后不能执行构建");
    }

    @Test
    @DisplayName("发布确认超时属于结果未知：不能当成队列已满，提示要说明可稍后刷新/重新提交")
    void shouldReportUnknownPublishOutcome() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        doThrow(new DeployMessageProducer.PublishException(
                "等待 broker 确认超时（5 秒）", new RuntimeException("timeout"), true))
                .when(producer).publish(any(DeployTaskMessage.class));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        BusinessException error = assertThrows(BusinessException.class, () -> manager.submit(1L, 2L));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("提交确认超时"), "实际=" + error.getMessage());
        assertTrue(error.getMessage().contains("重新提交"), "实际=" + error.getMessage());
    }

    @Test
    @DisplayName("PRECONDITION 是配置/声明错误，不能误报成队列已满（429）")
    void shouldNotClassifyPreconditionAsQueueFull() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        doThrow(new DeployMessageProducer.PublishException(
                "broker 拒绝接收部署任务：PRECONDITION_FAILED - inequivalent arg 'x-max-length'"))
                .when(producer).publish(any(DeployTaskMessage.class));
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        BusinessException error = assertThrows(BusinessException.class, () -> manager.submit(1L, 2L));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), error.getCode(),
                "PRECONDITION 属于配置错误，不是背压");
        assertTrue(error.getMessage().contains("消息队列暂不可用"), "实际=" + error.getMessage());
    }

    @Test
    @DisplayName("等应用锁的请求也算进 syncWaiting，健康接口不会漏报")
    void syncWaitingShouldIncludeLockWaiters() {
        DeployMessageProducer producer = mock(DeployMessageProducer.class);
        RabbitAdmin rabbitAdmin = mock(RabbitAdmin.class);
        DeployQueueManager manager = newManager(producer, rabbitAdmin);

        manager.syncLockWaitStarted();
        assertEquals(1, manager.syncWaitingCount());
        manager.syncLockWaitFinished();
        assertEquals(0, manager.syncWaitingCount());
    }
}
