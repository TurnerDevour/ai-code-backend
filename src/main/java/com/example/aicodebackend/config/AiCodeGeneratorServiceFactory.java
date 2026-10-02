package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource
    private StreamingChatModel streamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    /**
     * 使用 Caffeine 缓存来存储 AiCodeGeneratorService 实例,以 appId 为 key，AiCodeGeneratorService 为 value
     * 最大缓存数量为 1000，写入后 30 分钟过期，访问后 10 分钟过期
     */
    private final Cache<Long, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                // 当缓存项被移除时，可以在这里进行一些清理操作
                // 例如，关闭资源或记录日志
                log.info("AI 服务实例已被移除，appId: {}, 原因: {}", key, cause);
            })
            .build();


    /**
     * 根据 appId 从缓存中获取服务
     */
    public AiCodeGeneratorService getAiCodeGeneratorServiceWithCache(long appId) {
        return serviceCache.get(appId, this::createAiCodeGeneratorService);
    }

    /**
     * 根据 appId 创建服务
     *
     * @param appId 应用的唯一标识
     *
     * @return AiCodeGeneratorService 实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId) {
        log.info("Creating new AiCodeGeneratorService instance for appId: {}", appId);
        // 根据 appId 创建一个新的 MessageWindowChatMemory 实例
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(ChatHistoryConstant.MEMORY_MAX_MESSAGES)
                .build();

        // 对话记忆初始化时，从数据库中加载该应用的历史对话，保证 AI 能记住之前的上下文
        // appId 为 0 表示不关联具体应用的默认实例，无需加载历史
        if (appId > 0) {
            chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, ChatHistoryConstant.MEMORY_MAX_MESSAGES);
        }

        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemory(chatMemory)
                .build();
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorServiceWithCache(0L);
    }
}
