package com.example.aicodebackend.ai.provider;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阿里云百炼的模型工厂（DeepSeek 系列与 Qwen 系列都走这里）
 * 相对其它 OpenAI 兼容平台的差异只有两处：
 * 接口地址由 {@link BailianEndpointResolver} 拼装与校验，百炼专属请求参数在此透传。
 */
@Slf4j
@Component
public class BailianStreamingChatModelFactory extends AbstractStreamingChatModelFactory {

    /** 百炼思考模式开关的请求参数名 */
    static final String ENABLE_THINKING_PARAM = "enable_thinking";

    /** 百炼推理强度的请求参数名 */
    static final String REASONING_EFFORT_PARAM = "reasoning_effort";

    /** 百炼思考预算的请求参数名（单次请求允许用于内部推理的 token 上限） */
    static final String THINKING_BUDGET_PARAM = "thinking_budget";

    private final BailianEndpointResolver endpointResolver;

    public BailianStreamingChatModelFactory(BailianEndpointResolver endpointResolver) {
        this.endpointResolver = endpointResolver;
    }

    @Override
    public AIProviderEnum provider() {
        return AIProviderEnum.BAILIAN;
    }

    @Override
    protected String resolveBaseUrl(AiModelProperties.Model properties) {
        return endpointResolver.resolve(properties);
    }

    @Override
    protected void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                             AiModelProperties.Model properties) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (properties.getEnableThinking() != null) {
            parameters.put(ENABLE_THINKING_PARAM, properties.getEnableThinking());
        }
        if (StrUtil.isNotBlank(properties.getReasoningEffort())) {
            parameters.put(REASONING_EFFORT_PARAM, properties.getReasoningEffort().trim());
        }
        // 思考预算：给推理过程一个硬性上限，避免"模型反复推敲不收敛"时前端思考面板一直滚
        if (properties.getThinkingBudget() != null) {
            parameters.put(THINKING_BUDGET_PARAM, properties.getThinkingBudget());
        }
        if (parameters.isEmpty()) {
            return;
        }
        builder.customParameters(parameters);
        log.info("百炼模型 {} 显式设置请求参数：{}", properties.getModelName(), parameters);
    }
}
