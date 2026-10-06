package com.example.aicodebackend.config;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 对话记忆存储配置
 * <p>
 * 100 并发压测暴露的问题：Spring Session（Spring Data Redis/Jedis）与 langchain4j 社区版的
 * {@code RedisChatMemoryStore} 各自用默认的小连接池 + 2s 命令超时，并发下大量出现
 * {@code RedisConnectionFailureException: SocketTimeoutException: Read timed out}，
 * 表现为 SSE 接口返回 HTTP 500。
 * <p>
 * 这里换成自研的 {@link JedisChatMemoryStore}，连接池与命令超时全部可配置，
 * 并复用 Spring 的 {@code spring.data.redis.jedis.pool.*} 配置项，做到"一处配置、两处生效"。
 */
@Slf4j
@Configuration
public class RedisChatMemoryStoreConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.username:default}")
    private String username;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.ttl:3600}")
    private long ttl;

    @Value("${spring.data.redis.jedis.pool.max-active:64}")
    private int maxActive;

    @Value("${spring.data.redis.jedis.pool.max-idle:32}")
    private int maxIdle;

    @Value("${spring.data.redis.jedis.pool.min-idle:8}")
    private int minIdle;

    @Value("${spring.data.redis.jedis.pool.max-wait:10s}")
    private String maxWait;

    @Value("${spring.data.redis.timeout:10s}")
    private String timeout;

    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new JedisChatMemoryStore(host, port, username, password, ttl,
                maxActive, maxIdle, minIdle, parseDurationMillis(maxWait, 10_000L), (int) parseDurationMillis(timeout, 10_000L));
    }

    /**
     * 把 Spring 风格的时间字符串（如 10s / 500ms）解析成毫秒
     * <p>
     * 解析失败时回落到默认值，避免一个配置写法错误导致应用无法启动。
     *
     * @param value        配置值
     * @param defaultValue 默认毫秒数
     *
     * @return 毫秒数
     */
    private long parseDurationMillis(String value, long defaultValue) {
        try {
            return StringUtils.hasText(value) ? DurationStyle.detectAndParse(value).toMillis() : defaultValue;
        } catch (Exception e) {
            log.warn("Redis 时间配置无法解析，回落到默认值 {}ms，原始值: {}", defaultValue, value);
            return defaultValue;
        }
    }
}
