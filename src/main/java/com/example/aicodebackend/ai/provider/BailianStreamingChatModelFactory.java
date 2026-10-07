package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.model.enums.AIProviderEnum;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 阿里云百炼的模型工厂
 * <p>
 * 与 DeepSeek 的差异只有两处，都收敛在这里：
 * <ol>
 *     <li>接口地址：base_url 固定以 {@code /compatible-mode/v1} 结尾，业务空间 ID 在域名里，
 *     交给 {@link BailianEndpointResolver} 拼装与校验；</li>
 *     <li>思考模式开关：百炼用请求体里的 {@code enable_thinking} 控制（OpenAI 协议里没有这个字段，
 *     只能通过 {@code customParameters} 透传）。实测 qwen3.8-max / qwen3.7-plus 默认就开启思考，
 *     关掉能显著降低延迟与输出 token；不配置时保持平台默认，不做隐式干预。</li>
 * </ol>
 * 响应里的 {@code reasoning_content} 由父类打开的 return-thinking / send-thinking 处理，
 * 两个模型都会返回该字段，且实测回传历史（带 tools 的第二轮）不会像 DeepSeek 那样被拒。
 */
@Slf4j
@Component
public class BailianStreamingChatModelFactory extends AbstractStreamingChatModelFactory {

    /**
     * 百炼思考模式开关的请求参数名
     */
    static final String ENABLE_THINKING_PARAM = "enable_thinking";

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
        if (properties.getEnableThinking() == null) {
            return;
        }
        builder.customParameters(Map.of(ENABLE_THINKING_PARAM, properties.getEnableThinking()));
        log.info("百炼模型 {} 显式设置 {}={}", properties.getModelName(),
                ENABLE_THINKING_PARAM, properties.getEnableThinking());
    }
}
