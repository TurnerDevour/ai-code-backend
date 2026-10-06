package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.tools.*;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    /**
     * 按 AI 模型类型构建的流式模型，见 {@link AiModelConfig}。
     * <p>
     * 容器里共有多个 StreamingChatModel（含 starter 自动装配的那个），
     * 因此这里用 @Qualifier 按 bean 名精确注入，避免按类型注入产生歧义。
     */
    private final StreamingChatModel deepSeekFlashStreamingChatModel;

    private final StreamingChatModel deepSeekV4ProStreamingChatModel;

    private final ChatMemoryStore chatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    private final ToolManager toolManager;

    /**
     * 模型类型 -> 流式模型
     */
    private final Map<AIModelTypeEnum, StreamingChatModel> streamingChatModelMap;

    public AiCodeGeneratorServiceFactory(
            @Qualifier("deepSeekFlashStreamingChatModel") StreamingChatModel deepSeekFlashStreamingChatModel,
            @Qualifier("deepSeekV4ProStreamingChatModel") StreamingChatModel deepSeekV4ProStreamingChatModel,
            ToolManager toolManager,
            ChatMemoryStore chatMemoryStore,
            ChatHistoryService chatHistoryService
    ) {
        this.deepSeekFlashStreamingChatModel = deepSeekFlashStreamingChatModel;
        this.deepSeekV4ProStreamingChatModel = deepSeekV4ProStreamingChatModel;
        this.chatMemoryStore = chatMemoryStore;
        this.chatHistoryService = chatHistoryService;
        this.toolManager = toolManager;
        Map<AIModelTypeEnum, StreamingChatModel> map = new EnumMap<>(AIModelTypeEnum.class);
        map.put(AIModelTypeEnum.DEEPSEEK_FLASH, deepSeekFlashStreamingChatModel);
        map.put(AIModelTypeEnum.DEEPSEEK_V4_PRO, deepSeekV4ProStreamingChatModel);
        this.streamingChatModelMap = Collections.unmodifiableMap(map);
    }

    /**
     * 使用 Caffeine 缓存来存储 AiCodeGeneratorService 实例,以 appId + 代码生成类型 + 模型类型 为 key，
     * 最大缓存数量为 1000，写入后 30 分钟过期，访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                // 当缓存项被移除时，可以在这里进行一些清理操作
                log.info("AI 服务实例已被移除，cacheKey: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）这个方法是为了兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML, AIModelTypeEnum.DEEPSEEK_FLASH);
    }

    /**
     * 根据 appId 和代码生成类型获取服务（带缓存），使用默认模型 deepseek-flash
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        return getAiCodeGeneratorService(appId, codeGenType, AIModelTypeEnum.DEEPSEEK_FLASH);
    }

    /**
     * 根据 appId、代码生成类型和 AI 模型类型获取服务（带缓存）
     *
     * @param appId       应用 id
     * @param codeGenType 代码生成类型
     * @param aiModelType AI 模型类型，为空时回落到 deepseek-flash
     *
     * @return AI 代码生成服务
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType, AIModelTypeEnum aiModelType) {
        AIModelTypeEnum modelType = aiModelType == null ? AIModelTypeEnum.DEEPSEEK_FLASH : aiModelType;
        String cacheKey = buildCacheKey(appId, codeGenType, modelType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType, modelType));
    }

    /**
     * 失效某个应用缓存的生成服务
     * <p>
     * 使用场景：一次生成因为"assistant 消息带 tool_calls 却没有工具结果"这类记忆损坏而失败时，
     * 已经缓存的 {@link AiCodeGeneratorService} 内部持有损坏的记忆快照，不清掉它，
     * 该应用之后每次请求都会命中同一个坏实例（实测会一直失败到进程重启）。
     * 清掉缓存后，下一次请求会重新创建实例并从对话记忆重新加载（此时记忆已在读取时被清洗）。
     *
     * @param appId 应用 id
     *
     * @return 被失效的缓存项数量
     */
    public synchronized int invalidateAiCodeGeneratorService(long appId) {
        String prefix = appId + "_";
        java.util.List<String> keys = serviceCache.asMap().keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .toList();
        serviceCache.invalidateAll(keys);
        if (!keys.isEmpty()) {
            log.warn("已失效应用的生成服务缓存，appId: {}, 条目数: {}, keys: {}", appId, keys.size(), keys);
        }
        return keys.size();
    }

    /**
     * 实际创建服务实例（缓存未命中时调用）
     * <p>
     * 单独抽成 protected 方法是为了让单测可以子类化替换成桩服务：验证"缺少 CSS/JS 时的补全"
     * 不应该真的去调用模型。
     *
     * @param appId           应用 id
     * @param codeGenTypeEnum 代码生成类型
     * @param aiModelTypeEnum AI 模型类型
     *
     * @return AI 代码生成服务
     */
    protected AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum, AIModelTypeEnum aiModelTypeEnum) {
        log.info("Creating new AiCodeGeneratorService instance for appId: {}, codeGenType: {}, aiModelType: {}",
                appId, codeGenTypeEnum.getValue(), aiModelTypeEnum.getValue());
        // 根据 appId 创建一个新的 MessageWindowChatMemory 实例
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(ChatHistoryConstant.MEMORY_MAX_MESSAGES)
                .build();

        // 对话记忆初始化时，从数据库中加载该应用的历史对话，保证 AI 能记住之前的上下文
        // appId 为 0 表示不关联具体应用的默认实例，无需加载历史
        if (appId > 0) {
            chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, ChatHistoryConstant.MEMORY_MAX_MESSAGES);
        }

        // 按应用所选的 AI 模型类型取流式模型
        StreamingChatModel selectedStreamingChatModel = streamingChatModelMap.get(aiModelTypeEnum);
        if (selectedStreamingChatModel == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的 AI 模型类型: " + aiModelTypeEnum.getValue());
        }

        // 使用CodeGenTypeEnum来决定生成模式
        return switch (codeGenTypeEnum) {
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(selectedStreamingChatModel)
                    .chatMemoryProvider((memoryId) -> chatMemory)
                    .tools((Object[]) toolManager.getAllTools())
                    .hallucinatedToolNameStrategy(
                            toolExecutionRequest -> ToolExecutionResultMessage.from(toolExecutionRequest, "错误：不存在这个工具， " + toolExecutionRequest.name())
                    )
                    .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                    .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                    .build();
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(selectedStreamingChatModel)
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
     * @param aiModelType AI 模型类型枚举
     *
     * @return 缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType, AIModelTypeEnum aiModelType) {
        return appId + "_" + codeGenType.getValue() + "_" + aiModelType.getValue();
    }
}
