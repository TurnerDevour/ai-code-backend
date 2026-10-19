package com.example.aicodebackend.core.builder.mq;

import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.service.AppService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 部署任务消费者的 ACK / NACK 行为回归
 * 这几条分支没有 broker 也能测（Channel 是接口，直接 Mock），但它们是整套队列里最容易写错、……
 */
class DeployTaskConsumerTest {

    /** 与 DeployTaskConsumer.MAX_RETRY 对应：已重试 2 次时不再重投 */
    private static final int MAX_RETRY = 2;

    private static final long DELIVERY_TAG = 7L;

    private AppService appService;

    private DeployQueueManager deployQueueManager;

    private DeployMessageProducer producer;

    private Channel channel;

    private DeployTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        deployQueueManager = mock(DeployQueueManager.class);
        producer = mock(DeployMessageProducer.class);
        channel = mock(Channel.class);
        consumer = new DeployTaskConsumer(appService, deployQueueManager, producer);
    }

    private static DeployTaskMessage message() {
        return DeployTaskMessage.of(1001L, 1L, 2002L);
    }

    @Test
    @DisplayName("构建成功：ACK 一次，且执行计数被归零")
    void shouldAckAfterSuccessfulBuild() throws Exception {
        consumer.onDeployTask(message(), channel, DELIVERY_TAG, null, false);

        verify(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(deployQueueManager).taskStarted();
        verify(deployQueueManager).taskFinished();
    }

    @Test
    @DisplayName("taskId/attempt/redelivered 会传给服务层：重投消息凭 taskId 恢复同一任务")
    void shouldPassTaskMetadataToService() throws Exception {
        DeployTaskMessage message = message();
        consumer.onDeployTask(message, channel, DELIVERY_TAG, 2, true);

        ArgumentCaptor<DeployQueueManager.DeployTask> captor =
                ArgumentCaptor.forClass(DeployQueueManager.DeployTask.class);
        verify(appService).handleDeployTask(captor.capture());
        DeployQueueManager.DeployTask task = captor.getValue();
        assertEquals(2, task.attempt(), "重投次数必须透传，否则服务层无法从 deploying 重新抢占");
        assertEquals(message.taskId(), task.taskId(), "taskId 必须透传，否则无法判断消息是否属于当前任务");
        assertEquals(true, task.redelivered(), "redelivered 必须透传，崩溃重投才能恢复执行");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("基础设施异常：带上重试次数重投，并 ACK 掉原消息（不 requeue）")
    void shouldRepublishWithRetryHeaderOnInfrastructureError() throws Exception {
        doThrow(new IllegalStateException("数据库连接池耗尽"))
                .when(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));

        consumer.onDeployTask(message(), channel, DELIVERY_TAG, null, false);

        // 首次消费失败（retry=null）→ 以 retry=1 重新发布
        verify(producer).publish(any(DeployTaskMessage.class), eq(1));
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("重试次数用尽：NACK 进死信，不再重投")
    void shouldNackToDeadLetterWhenRetryExhausted() throws Exception {
        doThrow(new IllegalStateException("还是失败"))
                .when(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));

        consumer.onDeployTask(message(), channel, DELIVERY_TAG, MAX_RETRY, false);

        verify(producer, never()).publish(any(DeployTaskMessage.class), anyInt());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("重投本身失败：直接进死信，绝不用 requeue=true 打热循环")
    void shouldNackWhenRepublishFails() throws Exception {
        doThrow(new IllegalStateException("构建失败"))
                .when(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        doThrow(new DeployMessageProducer.PublishException("broker 拒绝接收部署任务：queue length limit exceeded"))
                .when(producer).publish(any(DeployTaskMessage.class), any(Integer.class));

        consumer.onDeployTask(message(), channel, DELIVERY_TAG, null, false);

        // requeue 必须是 false：true 会把消息塞回队头，坏消息在 prefetch=1 下变成死循环
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    @DisplayName("缺少 appId 的脏消息：直接 ACK 丢弃，不进入构建流程")
    void shouldAckAndDropWhenAppIdMissing() throws Exception {
        DeployTaskMessage dirty = new DeployTaskMessage(null, 1L, null, null, System.currentTimeMillis());

        consumer.onDeployTask(dirty, channel, DELIVERY_TAG, null, false);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(appService, never()).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        // 脏消息不该占用执行额度
        verify(deployQueueManager, never()).taskStarted();
    }

    @Test
    @DisplayName("无论成败都要归还执行计数（否则水位会一直显示有任务在跑）")
    void shouldAlwaysReleaseRunningCounter() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));

        consumer.onDeployTask(message(), channel, DELIVERY_TAG, null, false);

        verify(deployQueueManager, times(1)).taskStarted();
        verify(deployQueueManager, times(1)).taskFinished();
    }

    @Test
    @DisplayName("死信消息：ACK 并把应用落为 failed，不能只打日志")
    void shouldAckAndFailApplicationOnDeadLetter() throws Exception {
        // 回归压测发现的问题：死信监听器原来只打日志不 ACK，
        // 结果第一条死信永远 unacked，消费者再也拿不到后面的死信（1 unacked + 11 ready 卡死）
        DeployTaskMessage dead = message();
        consumer.onDeadLetter(dead, channel, DELIVERY_TAG);

        ArgumentCaptor<DeployQueueManager.DeployTask> captor =
                ArgumentCaptor.forClass(DeployQueueManager.DeployTask.class);
        verify(appService).handleDeadLetter(captor.capture());
        assertEquals(dead.taskId(), captor.getValue().taskId());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }
}
