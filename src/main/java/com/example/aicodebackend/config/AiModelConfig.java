package com.example.aicodebackend.config;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 模型配置。
 * <p>
 * 按 {@link AIModelTypeEnum} 手工构建每个模型的 StreamingChatModel，
 * 由 {@code AiCodeGeneratorServiceFactory} 依据应用所选的模型类型动态取用。
 * <p>
 * 不使用 starter 自动装配的 openAiStreamingChatModel：starter 的属性类没有暴露 sendThinking，
 * 而 DeepSeek 思考模式在带 tools 时要求回传历史轮次的 reasoning_content，
 * 缺少该开关会导致第二轮起报 400（The `reasoning_content` ... must be passed back to the API）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai")
public class AiModelConfig {

    /**
     * 模型类型（枚举 value）-> 模型配置
     */
    private Map<String, DeepSeekChatModelProperties> models = new HashMap<>();

    /**
     * 单个 DeepSeek 模型的配置项
     */
    @Data
    public static class DeepSeekChatModelProperties {

        private String baseUrl;

        private String apiKey;

        private String modelName;

        private Integer maxTokens;

        /**
         * 请求超时时间。
         * <p>
         * 必须显式绑定：OpenAiStreamingChatModel 对 connectTimeout / readTimeout 的默认值都是 60 秒
         * （getOrDefault(builder.timeout, ofSeconds(60))）。不配置就会按 60 秒生效，思考模式下
         * 单个请求一旦超过 60 秒就会被中断，表现为：
         * IOException: closed -> LangChain4jException: closed
         */
        private Duration timeout;

        /**
         * 是否把响应中的 reasoning_content 解析进 AiMessage.thinking()
         */
        private boolean returnThinking = true;

        /**
         * 是否在回放历史时把 thinking 作为 reasoning_content 回传给 API
         */
        private boolean sendThinking = true;

        private boolean logRequests = true;

        private boolean logResponses = true;

        private double temperature;
    }

    /**
     * deepseek-flash 模型
     */
    @Bean("deepSeekFlashStreamingChatModel")
    public StreamingChatModel deepSeekFlashStreamingChatModel() {
        return buildStreamingChatModel(AIModelTypeEnum.DEEPSEEK_FLASH);
    }

    /**
     * deepseek-v4-pro 模型
     */
    @Bean("deepSeekV4ProStreamingChatModel")
    public StreamingChatModel deepSeekV4ProStreamingChatModel() {
        return buildStreamingChatModel(AIModelTypeEnum.DEEPSEEK_V4_PRO);
    }

    /**
     * 根据配置构建流式模型
     *
     * @param aiModelType 模型类型
     *
     * @return 流式模型
     */
    private StreamingChatModel buildStreamingChatModel(AIModelTypeEnum aiModelType) {
        DeepSeekChatModelProperties properties = models.get(aiModelType.getValue());
        if (properties == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "缺少 AI 模型配置: langchain4j.open-ai.models." + aiModelType.getValue());
        }
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(SpringRestClient.builder())
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .maxTokens(properties.getMaxTokens())
                .temperature(properties.getTemperature())
                // 入站：把响应中的 reasoning_content 解析并保存到 AiMessage.thinking()
                .returnThinking(properties.isReturnThinking())
                // 出站：回放历史时回传 reasoning_content，否则 DeepSeek 思考模式 + tools 第二轮起报 400
                .sendThinking(properties.isSendThinking())
                // 覆盖 60 秒默认超时
                .timeout(properties.getTimeout())
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses())
                .build();
    }
}
