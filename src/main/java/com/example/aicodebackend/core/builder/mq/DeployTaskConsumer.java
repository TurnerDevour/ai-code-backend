package com.example.aicodebackend.core.builder.mq;

import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.service.AppService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 部署任务消费者
 * 并发控制：{@code concurrency = ${deploy.queue.workers}}，配合 {@code prefetch=1}，
 * 单实例最多同时构建 N 个任务。多实例部署时总并发 = 实例数 × N —— 这是"MQ 比进程内信号量更适合……
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployTaskConsumer {

    /** 基础设施异常的最大重试次数（不含首次消费） */
    private static final int MAX_RETRY = 2;

    private final AppService appService;

    private final DeployQueueManager deployQueueManager;

    private final DeployMessageProducer producer;

    /** 消费一条部署任务 */
    @RabbitListener(queues = DeployRabbitConfig.QUEUE, concurrency = "${deploy.queue.workers:4}")
    public void onDeployTask(DeployTaskMessage message,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                             @Header(name = DeployMessageProducer.HEADER_RETRY, required = false) Integer retry,
                             @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered)
            throws IOException {
        Long appId = message.appId();
        if (appId == null) {
            log.error("收到缺少 appId 的部署消息，直接丢弃：{}", message);
            channel.basicAck(deliveryTag, false);
            return;
        }
        int attempt = retry == null ? 0 : retry;
        boolean isRedelivered = Boolean.TRUE.equals(redelivered);
        deployQueueManager.taskStarted();
        try {
            log.info("开始消费部署任务，appId: {}, sequence: {}, taskId: {}, 排队时长(ms): {}, 重试次数: {}, 重投: {}",
                    appId, message.sequence(), message.taskId(), message.queuedMillis(), attempt, isRedelivered);
            appService.handleDeployTask(new DeployQueueManager.DeployTask(
                    appId, message.sequence(), message.taskId(), attempt, isRedelivered));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("部署任务消费异常，appId: {}, sequence: {}, 重试次数: {}",
                    appId, message.sequence(), attempt, e);
            try {
                if (attempt < MAX_RETRY) {
                    // 重新发布而不是 basicNack(requeue=true)：后者会把消息立刻塞回队头，
                    // 数据库不可用时会变成 CPU 打满的死循环
                    producer.publish(message, attempt + 1);
                    channel.basicAck(deliveryTag, false);
                } else {
                    log.error("部署任务重试 {} 次仍失败，转入死信队列，appId: {}, sequence: {}",
                            attempt, appId, message.sequence());
                    channel.basicNack(deliveryTag, false, false);
                }
            } catch (Exception publishError) {
                // 连重投都失败（例如队列又被填满 / broker 抖动）：不要 basicNack(requeue=true)，
                // 那会把消息立刻塞回队头形成热循环；直接进死信，由死信消费者把原始消息打出来
                log.error("重新发布部署任务失败，转入死信等待人工处理，appId: {}", appId, publishError);
                channel.basicNack(deliveryTag, false, false);
            }
        } finally {
            deployQueueManager.taskFinished();
        }
    }

    /**
     * 死信消费者：记录并把应用落为 failed，不重跑
     * 死信里的任务通常已经"重试过且仍然失败"，自动重跑没有意义；把原始消息完整打出来，
     * 并把应用状态落为 failed（失败原因写清楚），用户才能立刻重新提交，而不是等 30 分钟僵死判定……
     */
    @RabbitListener(queues = DeployRabbitConfig.DEAD_QUEUE)
    public void onDeadLetter(DeployTaskMessage message, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            log.error("部署任务进入死信队列，需要人工关注：appId={}, sequence={}, taskId={}, 提交时间={}, 排队时长(ms)={}",
                    message.appId(), message.sequence(), message.taskId(),
                    message.requestedAt(), message.queuedMillis());
            if (message.appId() != null) {
                // 落库失败不能阻塞 ACK：死信队列停摆比少一次状态更新更严重
                try {
                    appService.handleDeadLetter(new DeployQueueManager.DeployTask(
                            message.appId(), message.sequence(), message.taskId(), 0, false));
                } catch (Exception e) {
                    log.error("死信任务落库失败，应用状态可能仍是 queued/deploying，appId: {}",
                            message.appId(), e);
                }
            }
        } finally {
            // 记录完就确认：无法处理的消息留在队列里只会把后面的死信一起堵住
            channel.basicAck(deliveryTag, false);
        }
    }
}
