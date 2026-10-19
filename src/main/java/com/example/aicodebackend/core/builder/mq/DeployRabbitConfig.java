package com.example.aicodebackend.core.builder.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 部署构建队列的 RabbitMQ 拓扑
 * 结构（全部 durable，broker 重启后拓扑与消息都还在）：
 * <pre>……
 */
@Configuration
public class DeployRabbitConfig {

    /** 业务交换机（direct） */
    public static final String EXCHANGE = "deploy.exchange";

    /** 部署任务队列 */
    public static final String QUEUE = "deploy.queue";

    /** 死信交换机（direct） */
    public static final String DEAD_EXCHANGE = "deploy.dlx";

    /** 死信队列 */
    public static final String DEAD_QUEUE = "deploy.dlq";

    /** 业务路由键 */
    public static final String ROUTING_KEY = "deploy.build";

    /** 死信路由键 */
    public static final String DEAD_ROUTING_KEY = "deploy.dead";

    /**
     * 队列容量上限（与 {@code deploy.queue.max-queue-size} 对应）
     * 队列参数在声明时固化，因此这里用构造注入而不是在方法里读配置：保证"改配置必须重启"这件事
     * 在代码层面也是显式的。
     */
    private final int maxQueueSize;

    /**
     * 死信队列容量上限（{@code deploy.queue.max-dead-letter-size}）
     * 死信队列不设上限时，一旦消费端连续失败（例如依赖的服务长时间不可用），死信会无限堆积把磁盘吃满。
     * 溢出策略用 {@code drop-head}：保留最新的死信、丢掉最老的 —— 排查问题永远先看最近发生的。
     */
    private final int maxDeadLetterSize;

    public DeployRabbitConfig(@Value("${deploy.queue.max-queue-size:32}") int maxQueueSize,
                              @Value("${deploy.queue.max-dead-letter-size:1000}") int maxDeadLetterSize) {
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.maxDeadLetterSize = Math.max(1, maxDeadLetterSize);
    }

    /** 业务交换机 */
    @Bean
    public DirectExchange deployExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    /** 死信交换机 */
    @Bean
    public DirectExchange deployDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    /**
     * 部署任务队列：有界 + 溢出直接拒绝（不进死信）
     * 为什么溢出策略是 {@code reject-publish} 而不是 {@code reject-publish-dlx}：
     * 队列满属于正常的背压，用户当场就收到了"队列已满，请稍后再试"，消息没有必要再落一份到死信队列……
     */
    @Bean
    public Queue deployQueue() {
        return QueueBuilder.durable(QUEUE)
                // Spring AMQP 3.2 的 Overflow 枚举只有 dropHead / rejectPublish，
                // 这里直接写 x-overflow 参数，语义与枚举的 rejectPublish 一致（broker 回 basic.nack）
                .withArgument("x-overflow", "reject-publish")
                .maxLength(maxQueueSize)
                // 死信只接收"消费者显式 nack"的消息（重试耗尽）
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 死信队列：只有"消费端重试耗尽"的消息会落在这里，便于事后排查
     * 队列满（背压）不走死信，见 {@link #deployQueue()}。
     */
    @Bean
    public Queue deployDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE)
                // 有界：死信不设上限时，消费端长时间失败会把磁盘吃满
                .maxLength(maxDeadLetterSize)
                // 满了丢最老的：排查问题永远先看最近发生的死信
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    /** 业务绑定 */
    @Bean
    public Binding deployBinding(Queue deployQueue, DirectExchange deployExchange) {
        return BindingBuilder.bind(deployQueue).to(deployExchange).with(ROUTING_KEY);
    }

    /** 死信绑定 */
    @Bean
    public Binding deployDeadBinding(Queue deployDeadQueue, DirectExchange deployDeadExchange) {
        return BindingBuilder.bind(deployDeadQueue).to(deployDeadExchange).with(DEAD_ROUTING_KEY);
    }

    /**
     * 消息体用 JSON 序列化
     * 默认的 SimpleMessageConverter 只支持 String / byte[] / Serializable，且会用 Java 序列化，
     * 在队列里既不可读也不跨语言。换成 JSON 后可以直观看到 {@code {"appId":..,"sequence":..}}……
     */
    @Bean
    public MessageConverter deployMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
