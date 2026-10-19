package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.model.enums.AIProviderEnum;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 平台模型工厂：把一个模型的配置翻译成对应的 {@link StreamingChatModel}
 * 每个平台一个实现，平台差异（接口根地址怎么拼、要不要带平台专属请求参数）全部收敛在实现类里，
 * {@link StreamingChatModelRegistry} 只按 {@code provider} 找工厂，不关心平台细节。
 */
public interface StreamingChatModelFactory {

    /** 本工厂负责的平台 */
    AIProviderEnum provider();

    /** 构建流式模型 */
    StreamingChatModel create(AiModelProperties.Model properties);

    /**
     * 该平台"查询模型列表"的完整地址（OpenAI 兼容的 {@code GET /models}）
     * 供启动期可用性巡检使用；平台不支持时返回 null，巡检会自动跳过该平台。
     */
    default String modelsUrl(AiModelProperties.Model properties) {
        return null;
    }
}
