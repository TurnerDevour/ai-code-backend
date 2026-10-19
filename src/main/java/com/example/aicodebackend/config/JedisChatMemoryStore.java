package com.example.aicodebackend.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.exceptions.JedisException;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Jedis 连接池的对话记忆存储
 */
@Slf4j
public class JedisChatMemoryStore implements ChatMemoryStore {

    /** 单个 memoryId 的记忆过期时间（秒） */
    private final long ttlSeconds;

    private final RedisClient jedis;

    public JedisChatMemoryStore(String host, int port, String user, String password, long ttlSeconds,
                                int maxTotal, int maxIdle, int minIdle, long maxWaitMillis, int commandTimeoutMs) {
        this.ttlSeconds = ttlSeconds;

        GenericObjectPoolConfig<redis.clients.jedis.Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(java.time.Duration.ofMillis(maxWaitMillis));
        poolConfig.setTestOnBorrow(true);

        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(commandTimeoutMs)
                .socketTimeoutMillis(commandTimeoutMs)
                .user(user)
                .password(password)
                .build();

        this.jedis = RedisClient.builder()
                .hostAndPort(new HostAndPort(host, port))
                .clientConfig(clientConfig)
                .poolConfig(poolConfig)
                .build();
        log.info("对话记忆存储初始化完成：redis={}:{}, maxTotal={}, maxIdle={}, minIdle={}, maxWait={}ms, commandTimeout={}ms, ttl={}s",
                host, port, maxTotal, maxIdle, minIdle, maxWaitMillis, commandTimeoutMs, ttlSeconds);
    }

    /**
     * 读取某个会话的全部消息
     * 这里必须是纯读取，绝不能做"工具消息配对清洗"：LangChain4j 的工具循环是
     * "先把 assistant(tool_calls) 写进记忆 → 执行工具 → 再把工具结果写进记忆"
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = toRedisKey(memoryId);
        try {
            String json = jedis.get(key);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (JedisException e) {
            log.error("读取对话记忆失败，key: {}", key, e);
            return new ArrayList<>();
        }
    }

    /** 覆盖写入某个会话的全部消息，并续期 TTL */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = toRedisKey(memoryId);
        String json = ChatMessageSerializer.messagesToJson(messages);
        try {
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, json);
            } else {
                jedis.set(key, json);
            }
        } catch (JedisException e) {
            log.error("写入对话记忆失败，key: {}, 消息条数: {}", key, messages == null ? 0 : messages.size(), e);
        }
    }

    /** 删除某个会话的全部消息 */
    @Override
    public void deleteMessages(Object memoryId) {
        String key = toRedisKey(memoryId);
        try {
            jedis.del(key);
        } catch (JedisException e) {
            log.error("删除对话记忆失败，key: {}", key, e);
        }
    }

    /** 构建 Redis key（与社区版保持一致，不加前缀，便于复用历史数据） */
    private String toRedisKey(Object memoryId) {
        return String.valueOf(memoryId);
    }
}
