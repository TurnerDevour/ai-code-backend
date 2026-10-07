package com.example.aicodebackend.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Jedis 连接池的对话记忆存储
 * <p>
 * 为什么不直接用 langchain4j 社区版的 {@code RedisChatMemoryStore}：
 * 它的 Builder 只暴露 host/port/user/password/ttl/prefix/storeType，
 * <b>不接受连接池与超时配置</b>，内部用默认池 + 默认命令超时。
 * 100 并发压测中，它与 Spring Session 各自的默认小连接池被同时打满，
 * 大量出现 {@code RedisConnectionFailureException: SocketTimeoutException: Read timed out}，
 * 最终表现为 SSE 接口返回 HTTP 500。
 * <p>
 * 这里自己实现 {@link ChatMemoryStore}，把连接池、命令超时都变成可配置项；
 * 存储格式与社区版保持一致（value 为 {@code ChatMessageSerializer.messagesToJson} 的结果，
 * key 为 memoryId 字符串、带 TTL），因此对已有数据完全兼容。
 */
@Slf4j
public class JedisChatMemoryStore implements ChatMemoryStore {

    /**
     * 单个 memoryId 的记忆过期时间（秒）
     */
    private final long ttlSeconds;

    private final JedisPooled jedis;

    /**
     * @param host              Redis 主机
     * @param port              Redis 端口
     * @param user              Redis ACL 用户名
     * @param password          Redis 密码
     * @param ttlSeconds        记忆过期时间（秒），小于等于 0 表示不过期
     * @param maxTotal          连接池最大连接数
     * @param maxIdle           连接池最大空闲连接数
     * @param minIdle           连接池最小空闲连接数
     * @param maxWaitMillis     获取连接的最长等待时间（毫秒）
     * @param commandTimeoutMs  命令读写超时（毫秒）
     */
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

        this.jedis = new JedisPooled(new HostAndPort(host, port), clientConfig, poolConfig);
        log.info("对话记忆存储初始化完成：redis={}:{}, maxTotal={}, maxIdle={}, minIdle={}, maxWait={}ms, commandTimeout={}ms, ttl={}s",
                host, port, maxTotal, maxIdle, minIdle, maxWaitMillis, commandTimeoutMs, ttlSeconds);
    }

    /**
     * 读取某个会话的全部消息
     * <p>
     * <b>这里必须是纯读取，绝不能做"工具消息配对清洗"</b>：LangChain4j 的工具循环是
     * "先把 assistant(tool_calls) 写进记忆 → 执行工具 → 再把工具结果写进记忆"，
     * 而 {@code MessageWindowChatMemory.add()} 每一次都会先通过本方法读一遍存储。
     * 也就是说，在"结果还没写进来"的那一瞬间读到的记忆<b>天然</b>就是
     * "有 tool_calls、没有结果"——这是正在进行的正常状态，不是脏数据。
     * <p>
     * 历史教训（实测死循环）：这里曾经调用 {@code ChatMemorySanitizer.sanitizeToolMessages} 并写回，
     * 于是每一次工具结果写回前，刚写进去的 assistant 消息就被当成"未完成"删掉，
     * 紧接着孤立的工具结果也在下一次读取时被删掉：模型<b>永远看不到工具结果</b>，
     * 以为文件根本没写成功，于是反复重写同样的文件，Vue 工程模式的生成永不收敛。
     * <p>
     * 脏数据（上一轮异常中断留下的不完整工具上下文）只在"新一轮开始前"修复一次，
     * 见 {@link ChatMemorySanitizer#repairStoredMessages}。
     *
     * @param memoryId 记忆 id（本项目中为 appId）
     *
     * @return 消息列表，不存在或读取失败时返回空列表（记忆读失败不应该让整个生成请求失败）
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

    /**
     * 覆盖写入某个会话的全部消息，并续期 TTL
     *
     * @param memoryId 记忆 id（本项目中为 appId）
     * @param messages 消息列表
     */
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

    /**
     * 删除某个会话的全部消息
     *
     * @param memoryId 记忆 id（本项目中为 appId）
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String key = toRedisKey(memoryId);
        try {
            jedis.del(key);
        } catch (JedisException e) {
            log.error("删除对话记忆失败，key: {}", key, e);
        }
    }

    /**
     * 构建 Redis key（与社区版保持一致，不加前缀，便于复用历史数据）
     */
    private String toRedisKey(Object memoryId) {
        return String.valueOf(memoryId);
    }
}
