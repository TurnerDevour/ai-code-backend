package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.tools.FileWriteTool;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
    private StreamingChatModel openAiStreamingChatModel;

    @Resource
    private StreamingChatModel reasoningStreamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    /**
     * 使用 Caffeine 缓存来存储 AiCodeGeneratorService 实例,以 appId 为 key，AiCodeGeneratorService 为 value
     * 最大缓存数量为 1000，写入后 30 分钟过期，访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                // 当缓存项被移除时，可以在这里进行一些清理操作
                // 例如，关闭资源或记录日志
                log.info("AI 服务实例已被移除，cacheKey: {}, 原因: {}", key, cause);
            })
            .build();


    /**
     * 根据appId获取服务（带缓存）这个方法是为了兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId和代码生成类型获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    /**
     * 根据 appId 创建服务
     *
     * @param appId           应用的唯一标识
     * @param codeGenTypeEnum 代码生成类型枚举
     *
     * @return AiCodeGeneratorService 实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum) {
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

        // 使用CodeGenTypeEnum来决定生成模式
        return switch (codeGenTypeEnum) {
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(reasoningStreamingChatModel)
                    .chatMemoryProvider((memoryId) -> chatMemory)
                    .tools(new FileWriteTool())
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(toolExecutionRequest, "错误：不存在这个工具， " + toolExecutionRequest.name()))
                    .build();
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemory(chatMemory)
                    .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }

    /**
     * 构建缓存键
     *
     * @param appId       应用的唯一标识
     * @param codeGenType 代码生成类型枚举
     *
     * @return 缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }
}
