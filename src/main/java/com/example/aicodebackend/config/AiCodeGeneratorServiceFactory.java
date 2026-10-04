package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.tools.FileWriteTool;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
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
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
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
     * 非流式模型，由 starter 自动装配
     */
    private final ChatModel chatModel;

    /**
     * 按 AI 模型类型构建的流式模型，见 {@link AiModelConfig}。
     * <p>
     * 容器里共有多个 StreamingChatModel（含 starter 自动装配的那个），
     * 因此这里用 @Qualifier 按 bean 名精确注入，避免按类型注入产生歧义。
     */
    private final StreamingChatModel deepSeekFlashStreamingChatModel;

    private final StreamingChatModel deepSeekV4ProStreamingChatModel;

    private final RedisChatMemoryStore redisChatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    /**
     * 模型类型 -> 流式模型
     */
    private final Map<AIModelTypeEnum, StreamingChatModel> streamingChatModelMap;

    public AiCodeGeneratorServiceFactory(
            ChatModel chatModel,
            @Qualifier("deepSeekFlashStreamingChatModel") StreamingChatModel deepSeekFlashStreamingChatModel,
            @Qualifier("deepSeekV4ProStreamingChatModel") StreamingChatModel deepSeekV4ProStreamingChatModel,
            RedisChatMemoryStore redisChatMemoryStore,
            ChatHistoryService chatHistoryService) {
        this.chatModel = chatModel;
        this.deepSeekFlashStreamingChatModel = deepSeekFlashStreamingChatModel;
        this.deepSeekV4ProStreamingChatModel = deepSeekV4ProStreamingChatModel;
        this.redisChatMemoryStore = redisChatMemoryStore;
        this.chatHistoryService = chatHistoryService;
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
                // 例如，关闭资源或记录日志
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
     * @param appId        应用 id
     * @param codeGenType  代码生成类型
     * @param aiModelType  AI 模型类型，为空时回落到 deepseek-flash
     *
     * @return AI 代码生成服务
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType, AIModelTypeEnum aiModelType) {
        AIModelTypeEnum modelType = aiModelType == null ? AIModelTypeEnum.DEEPSEEK_FLASH : aiModelType;
        String cacheKey = buildCacheKey(appId, codeGenType, modelType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType, modelType));
    }

    /**
     * 根据 appId 创建服务
     *
     * @param appId           应用的唯一标识
     * @param codeGenTypeEnum 代码生成类型枚举
     * @param aiModelTypeEnum AI 模型类型枚举
     *
     * @return AiCodeGeneratorService 实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum, AIModelTypeEnum aiModelTypeEnum) {
        log.info("Creating new AiCodeGeneratorService instance for appId: {}, codeGenType: {}, aiModelType: {}",
                appId, codeGenTypeEnum.getValue(), aiModelTypeEnum.getValue());
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
                    .tools(new FileWriteTool())
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(toolExecutionRequest, "错误：不存在这个工具， " + toolExecutionRequest.name()))
                    // langchain4j 1.21.0 起必须显式指定工具错误处理策略，否则会打印警告并沿用即将变更的默认行为。
                    // 工具入参解析失败（例如流式返回的 arguments 不完整）应把原因回给模型，让它自行纠正后重试；
                    // 工具执行期的异常则只在明确面向 LLM 时透出，其余情况直接让本次调用失败，避免泄露内部细节。
                    .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                    .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                    .build();
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
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
