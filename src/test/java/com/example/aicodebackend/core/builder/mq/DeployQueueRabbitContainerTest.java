package com.example.aicodebackend.core.builder.mq;

import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.service.AppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 部署队列的集成测试（真实 RabbitMQ，不是 Mock）
 * 单元测试只能证明"我们调用了 basicAck / basicNack"，证明不了下面这些只有真 broker 才能验证的事……
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeployQueueRabbitContainerTest {

    /** 与生产同一个镜像：队列参数在 4.3 上被接受才算数 */
    private static final String IMAGE = "rabbitmq:4.3-management";

    private static final String USER = "ai_code";

    private static final String PASSWORD = "check_pwd";

    /** 生产是 32；这里故意设成 1，才能稳定复现"队列满 → broker nack" */
    private static final int MAX_QUEUE_SIZE = 1;

    /** 死信队列容量：给小值便于验证"有界"参数被 broker 接受 */
    private static final int MAX_DEAD_LETTER_SIZE = 100;

    private static final long AWAIT_TIMEOUT_MS = 15_000L;

    /** 外部 broker（环境变量提供）或 Testcontainers 启动的容器；两者都拿不到时为 null → 跳过 */
    private static RabbitMQContainer container;

    private static String brokerHost;

    private static int brokerPort;

    private static String brokerUser;

    private static String brokerPassword;

    static {
        String envHost = trimToNull(System.getenv("RABBITMQ_TEST_HOST"));
        if (envHost != null) {
            brokerHost = envHost;
            brokerPort = Integer.parseInt(envOrDefault("RABBITMQ_TEST_PORT", "5672"));
            brokerUser = envOrDefault("RABBITMQ_TEST_USER", USER);
            brokerPassword = envOrDefault("RABBITMQ_TEST_PASSWORD", PASSWORD);
        } else {
            try {
                container = new RabbitMQContainer(IMAGE)
                        .withAdminUser(USER)
                        .withAdminPassword(PASSWORD);
                container.start();
                brokerHost = container.getHost();
                brokerPort = container.getAmqpPort();
                brokerUser = USER;
                brokerPassword = PASSWORD;
            } catch (Throwable e) {
                // Docker 不可用（或 Docker Desktop 与 docker-java 版本不兼容）：交给 @BeforeEach 跳过
                container = null;
                brokerHost = null;
                System.out.println("[DeployQueueRabbitContainerTest] 无法启动 RabbitMQ 容器，将跳过本类："
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private CachingConnectionFactory connectionFactory;

    private RabbitAdmin admin;

    private Jackson2JsonMessageConverter converter;

    private RabbitTemplate template;

    private DeployMessageProducer producer;

    private SimpleMessageListenerContainer listener;

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = trimToNull(System.getenv(key));
        return value == null ? defaultValue : value;
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(brokerHost != null,
                "没有可用的 RabbitMQ：Docker 不可用且未设置 RABBITMQ_TEST_HOST，跳过集成测试");

        connectionFactory = new CachingConnectionFactory(brokerHost, brokerPort);
        connectionFactory.setUsername(brokerUser);
        connectionFactory.setPassword(brokerPassword);
        // 与 application-*.yaml 等价：生产端确认 + 不可路由回退
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);

        admin = new RabbitAdmin(connectionFactory);
        declareTopology();
        purgeQueues();

        producer = new DeployMessageProducer(template);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
            listener = null;
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }

    /** 用生产里同一份 {@link DeployRabbitConfig} 声明拓扑（容量改成 1，死信容量给小值便于验证有界） */
    private void declareTopology() {
        DeployRabbitConfig config = new DeployRabbitConfig(MAX_QUEUE_SIZE, MAX_DEAD_LETTER_SIZE);
        admin.declareExchange(config.deployExchange());
        admin.declareExchange(config.deployDeadExchange());
        admin.declareQueue(config.deployQueue());
        admin.declareQueue(config.deployDeadQueue());
        admin.declareBinding(config.deployBinding(config.deployQueue(), config.deployExchange()));
        admin.declareBinding(config.deployDeadBinding(config.deployDeadQueue(), config.deployDeadExchange()));
    }

    private void purgeQueues() {
        admin.purgeQueue(DeployRabbitConfig.QUEUE, true);
        admin.purgeQueue(DeployRabbitConfig.DEAD_QUEUE, true);
    }

    /** 队列里"就绪"（未被消费）的消息数 */
    private int readyCount(String queue) {
        var info = admin.getQueueInfo(queue);
        assertNotNull(info, "队列不存在：" + queue);
        return info.getMessageCount();
    }

    /** 轮询等待条件成立（broker 的计数更新有毫秒级延迟，直接断言会偶发失败） */
    private static void awaitTrue(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + description, e);
            }
        }
        throw new AssertionError("等待超时：" + description);
    }

    private void startConsumer(AppService appService) {
        DeployQueueManager manager = mock(DeployQueueManager.class);
        DeployTaskConsumer consumer = new DeployTaskConsumer(appService, manager, producer);

        listener = new SimpleMessageListenerContainer(connectionFactory);
        listener.setQueueNames(DeployRabbitConfig.QUEUE);
        // 与生产一致：手动 ACK + prefetch 1
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setPrefetchCount(1);
        listener.setConcurrentConsumers(1);
        listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            DeployTaskMessage payload = (DeployTaskMessage) converter.fromMessage(message);
            Integer retry = (Integer) message.getMessageProperties()
                    .getHeaders().get(DeployMessageProducer.HEADER_RETRY);
            consumer.onDeployTask(payload, channel, message.getMessageProperties().getDeliveryTag(), retry,
                    message.getMessageProperties().isRedelivered());
        });
        listener.start();
    }

    @Test
    @Order(1)
    @DisplayName("拓扑：参数被 4.3 接受、重复声明不报错；消息是 JSON 且持久化")
    void shouldDeclareTopologyAndPublishJsonMessage() {
        producer.publish(DeployTaskMessage.of(1001L, 1L, 2002L));

        awaitTrue("消息进入 deploy.queue", () -> readyCount(DeployRabbitConfig.QUEUE) == 1);

        // 重复声明同一份参数：等价则静默通过，不等价 broker 会抛 PRECONDITION_FAILED
        declareTopology();

        Message raw = template.receive(DeployRabbitConfig.QUEUE);
        assertNotNull(raw, "应当能取到消息");
        String body = new String(raw.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith("{"), "消息体必须是 JSON（不能是 Java 序列化），实际=" + body);
        assertTrue(body.contains("\"appId\":1001"), "JSON 里应包含 appId，实际=" + body);
        // 注意用 receivedDeliveryMode：消费端拿到的持久化标记在 received* 字段里（getDeliveryMode() 是发送侧用的）
        assertEquals(MessageDeliveryMode.PERSISTENT, raw.getMessageProperties().getReceivedDeliveryMode(),
                "消息必须持久化，否则 broker 重启就丢；实际属性=" + raw.getMessageProperties());
    }

    @Test
    @Order(2)
    @DisplayName("队列满：broker 拒绝（nack）→ 生产者抛业务异常，被拒消息不进死信（背压不是故障）")
    void shouldRejectWhenQueueFullWithoutTouchingDeadLetter() {
        producer.publish(DeployTaskMessage.of(1001L, 1L, null));
        awaitTrue("第一条消息占满队列", () -> readyCount(DeployRabbitConfig.QUEUE) == 1);

        DeployMessageProducer.PublishException error = assertThrows(
                DeployMessageProducer.PublishException.class,
                () -> producer.publish(DeployTaskMessage.of(1002L, 2L, null)),
                "队列已满时 broker 会 nack，生产者必须抛异常而不是假装成功");
        assertTrue(error.getMessage().contains("拒绝接收"), "异常信息应说明是 broker 拒收，实际=" + error.getMessage());

        // x-overflow=reject-publish：消息被 broker 直接拒绝，不落死信（死信只留给"消费端重试耗尽"）
        assertEquals(0, readyCount(DeployRabbitConfig.DEAD_QUEUE),
                "队列满属于正常背压，不应污染死信队列；实际死信=" + readyCount(DeployRabbitConfig.DEAD_QUEUE));
        assertEquals(1, readyCount(DeployRabbitConfig.QUEUE), "原队列仍应只有占满的那一条");
    }

    @Test
    @Order(3)
    @DisplayName("消费成功：真实 Channel 上 ACK 生效，队列归零")
    void shouldConsumeAndAckWithRealChannel() {
        AppService appService = mock(AppService.class);
        startConsumer(appService);

        producer.publish(DeployTaskMessage.of(1001L, 1L, null));

        verify(appService, timeout(AWAIT_TIMEOUT_MS)).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        awaitTrue("ACK 之后队列应为空", () -> readyCount(DeployRabbitConfig.QUEUE) == 0);
    }

    @Test
    @Order(4)
    @DisplayName("重试耗尽：消费者 NACK(requeue=false)，消息进入死信而不是回到队头")
    void shouldMoveToDeadLetterWhenRetryExhausted() {
        AppService appService = mock(AppService.class);
        doThrow(new IllegalStateException("数据库不可用"))
                .when(appService).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        startConsumer(appService);

        // 带 x-retry=2（即已达到 DeployTaskConsumer 的最大重试次数）发布
        DeployTaskMessage message = DeployTaskMessage.of(1001L, 1L, null);
        template.convertAndSend(DeployRabbitConfig.EXCHANGE, DeployRabbitConfig.ROUTING_KEY, message, m -> {
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            m.getMessageProperties().setHeader(DeployMessageProducer.HEADER_RETRY, 2);
            return m;
        });

        verify(appService, timeout(AWAIT_TIMEOUT_MS)).handleDeployTask(any(DeployQueueManager.DeployTask.class));
        awaitTrue("重试耗尽的消息应进入 deploy.dlq", () -> readyCount(DeployRabbitConfig.DEAD_QUEUE) == 1);
        awaitTrue("主队列应被清空", () -> readyCount(DeployRabbitConfig.QUEUE) == 0);
    }
}
