package com.example.aicodebackend.core.builder.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.Serial;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 部署任务消息的生产者
 * 与"发出去就不管"的写法不同，这里会同步等待 broker 的 publisher confirm，原因很实际：
 * 提交部署是一个用户可见的操作，必须当场告诉用户"受理成功 / 队列已满 / 系统异常"，否则用户会以为部署已经排队了，结果实际上根本没入队。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployMessageProducer {

    /** 重试次数头：消费失败需要再试一次时，用它记录已经试过几次，避免无限重投 */
    public static final String HEADER_RETRY = "x-retry";

    /**
     * 等待 broker 确认的最长时间（秒）
     * 取 5 秒是为了容忍 broker 短时抖动（例如刚好在做队列同步），同时不至于让提交接口卡太久。
     */
    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final RabbitTemplate rabbitTemplate;

    /** 发布一个部署任务（首次入队） */
    public void publish(DeployTaskMessage message) {
        publish(message, 0);
    }

    /** 发布一个部署任务（可带重试次数） */
    public void publish(DeployTaskMessage message, int retry) {
        String correlationId = "deploy-" + message.appId() + "-" + message.sequence();
        CorrelationData correlation = new CorrelationData(correlationId);
        try {
            rabbitTemplate.convertAndSend(DeployRabbitConfig.EXCHANGE, DeployRabbitConfig.ROUTING_KEY, message,
                    // 手动 ACK 模式下消息必须显式声明持久化，否则 broker 重启后消息就没了
                    m -> {
                        MessageProperties properties = m.getMessageProperties();
                        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        if (retry > 0) {
                            properties.setHeader(HEADER_RETRY, retry);
                        }
                        return m;
                    },
                    correlation);
        } catch (AmqpException e) {
            throw new PublishException("发布部署任务消息失败：" + e.getMessage(), e);
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("等待 broker 确认被中断", e, true);
        } catch (ExecutionException | TimeoutException e) {
            // 超时/连接中断时消息可能已经写入 broker，只是确认没回来：属于"结果未知"，
            // 调用方不能把它当成确定失败直接回滚（详见 DeployQueueManager#submit）
            throw new PublishException("等待 broker 确认超时（" + CONFIRM_TIMEOUT_SECONDS + " 秒）", e, true);
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "未收到确认" : confirm.getReason();
            // 队列满了（x-max-length + reject-publish）时走的就是这里
            throw new PublishException("broker 拒绝接收部署任务：" + reason);
        }
        // mandatory=true 时，交换机找不到可用队列会回退一条 ReturnedMessage；此时 broker 仍然 confirm 了，
        // 只是消息没有进入任何队列 —— 不能只看 confirm，否则会以为受理成功
        if (correlation.getReturned() != null) {
            throw new PublishException("部署任务没有可投递的队列（检查 exchange / binding 是否声明成功）");
        }
        log.info("部署任务已入队，appId: {}, sequence: {}, 重试次数: {}, 排队时长(ms): {}",
                message.appId(), message.sequence(), retry, message.queuedMillis());
    }

    /**
     * 消息发布失败
     * 单独定义成异常类型是为了让上层（{@code DeployQueueManager}）能把"基础设施失败"
     * 翻译成用户看得懂的业务提示（队列已满 / 服务不可用），而不是把 AMQP 的原始异常抛给前端。
     */
    public static class PublishException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 发布结果是否未知：true 表示消息可能已经入队（确认超时/中断），调用方不能直接当成失败回滚 */
        private final boolean outcomeUnknown;

        public PublishException(String message) {
            this(message, null, false);
        }

        public PublishException(String message, Throwable cause) {
            this(message, cause, false);
        }

        public PublishException(String message, Throwable cause, boolean outcomeUnknown) {
            super(message, cause);
            this.outcomeUnknown = outcomeUnknown;
        }

        public boolean isOutcomeUnknown() {
            return outcomeUnknown;
        }
    }
}
