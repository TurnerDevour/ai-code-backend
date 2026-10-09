package com.example.aicodebackend.ai.provider;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * OpenAI 兼容协议平台的公共构建逻辑
 * <p>
 * 阿里云百炼的各个模型都提供 OpenAI 兼容接口，请求体与响应体结构一致，差别只在
 * "接口根地址怎么得到"和"要不要带平台专属参数"，所以这里用模板方法把公共部分固定下来，
 * 子类只实现这两个钩子。
 * <p>
 * 不使用 starter 自动装配的 {@code openAiStreamingChatModel}：starter 的属性类没有暴露
 * {@code sendThinking}，而思考模式的模型在带 tools 的多轮里对历史轮次的 reasoning_content
 * 有要求，缺少该开关就只能二选一（要么全部回传、要么全部不回传）。
 */
@Slf4j
public abstract class AbstractStreamingChatModelFactory implements StreamingChatModelFactory {

    /**
     * timeout 配成空值时的兜底超时
     */
    private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(600);

    @Override
    public StreamingChatModel create(AiModelProperties.Model properties) {
        validate(properties);
        String baseUrl = resolveBaseUrl(properties);
        Duration timeout = resolveTimeout(properties);
        var builder = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(SpringRestClient.builder())
                .baseUrl(baseUrl)
                .apiKey(properties.getApiKey().trim())
                .modelName(properties.getModelName().trim())
                .temperature(properties.getTemperature())
                // 入站：把响应中的 reasoning_content 解析并保存到 AiMessage.thinking()
                .returnThinking(properties.isReturnThinking())
                // 出站：回放历史时回传 reasoning_content
                .sendThinking(properties.isSendThinking())
                // 覆盖 60 秒默认超时
                .timeout(timeout)
                .logRequests(properties.isLogRequests())
                .logResponses(properties.isLogResponses());
        if (properties.getMaxTokens() != null) {
            builder.maxTokens(properties.getMaxTokens());
        }
        customize(builder, properties);
        StreamingChatModel model = builder.build();
        log.info("已构建 AI 模型：provider={}, modelName={}, baseUrl={}, maxTokens={}, timeout={}",
                provider().getValue(), properties.getModelName(), baseUrl, properties.getMaxTokens(), timeout);
        return model;
    }

    @Override
    public String modelsUrl(AiModelProperties.Model properties) {
        // OpenAI 兼容协议固定是 GET {baseUrl}/models
        return resolveBaseUrl(properties) + "/models";
    }

    /**
     * 解析该平台的接口根地址（子类实现，平台差异的入口）
     *
     * @param properties 单模型配置
     *
     * @return 接口根地址
     */
    protected abstract String resolveBaseUrl(AiModelProperties.Model properties);

    /**
     * 平台专属的构建参数（子类按需覆写，默认不加任何参数）
     *
     * @param builder    模型构建器
     * @param properties 单模型配置
     */
    protected void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                             AiModelProperties.Model properties) {
        // 默认无平台专属参数
    }

    /**
     * 校验必填项
     * <p>
     * 校验放在"构建时"而不是"绑定时"：一个模型的配置写错只让该模型不可用，
     * 其它模型照样能用（见 {@link StreamingChatModelRegistry}），应用也不会起不来。
     *
     * @param properties 单模型配置
     */
    protected void validate(AiModelProperties.Model properties) {
        if (properties == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 模型配置为空");
        }
        // 先校验模型名：下面拼报错信息时要用它定位是哪个模型
        if (isUnresolved(properties.getModelName())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "缺少模型名：请配置 ai.models.*.model-name");
        }
        if (isUnresolved(properties.getApiKey())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "缺少 API Key：请配置 ai.models.<" + properties.getModelName() + ">.api-key 或对应的环境变量");
        }
    }

    /**
     * 超时兜底
     *
     * @param properties 单模型配置
     *
     * @return 非空超时
     */
    private Duration resolveTimeout(AiModelProperties.Model properties) {
        if (properties.getTimeout() == null) {
            log.warn("模型 {} 未配置 timeout，按 {} 兜底（框架默认 60 秒对思考模式太短）",
                    properties.getModelName(), FALLBACK_TIMEOUT);
            return FALLBACK_TIMEOUT;
        }
        return properties.getTimeout();
    }

    /**
     * 判断配置值是否缺失
     * <p>
     * 除了空白，还要把 {@code ${ALI_AI_API_KEY}} 这种"环境变量没配、Spring 原样保留占位符"的情况
     * 也当成缺失：{@code @ConfigurationProperties} 的绑定默认忽略无法解析的占位符，
     * 不识别出来的话，模型会带着一串 {@code ${...}} 去请求，报错信息对排查毫无帮助。
     *
     * @param value 配置值
     *
     * @return 缺失返回 true
     */
    public static boolean isUnresolved(String value) {
        if (StrUtil.isBlank(value)) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }
}
