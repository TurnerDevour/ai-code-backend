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
 * <p>
 * 相对"其它 OpenAI 兼容平台"的差异只有两处，都收敛在这里：
 * <ol>
 *     <li>接口地址：base_url 固定以 {@code /compatible-mode/v1} 结尾，业务空间 ID 在域名里，
 *     交给 {@link BailianEndpointResolver} 拼装与校验；</li>
 *     <li>百炼专属的请求参数：思考模式开关 {@code enable_thinking} 与推理强度 {@code reasoning_effort}。
 *     前者不是 OpenAI 协议里的字段，只能通过 {@code customParameters} 透传进请求体；
 *     后者虽是标准字段，也一并走同一条通道，免得同一类参数出现两种写法。</li>
 * </ol>
 * 响应里的 {@code reasoning_content} 由父类打开的 return-thinking / send-thinking 处理，
 * 百炼的模型都会返回该字段；历史轮次要不要回传由配置里的 send-thinking 决定（yaml 里统一关闭）。
 */
@Slf4j
@Component
public class BailianStreamingChatModelFactory extends AbstractStreamingChatModelFactory {

    /**
     * 百炼思考模式开关的请求参数名
     */
    static final String ENABLE_THINKING_PARAM = "enable_thinking";

    /**
     * 百炼推理强度的请求参数名
     */
    static final String REASONING_EFFORT_PARAM = "reasoning_effort";

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
        if (parameters.isEmpty()) {
            return;
        }
        builder.customParameters(parameters);
        log.info("百炼模型 {} 显式设置请求参数：{}", properties.getModelName(), parameters);
    }
}
