package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 开放平台的模型工厂
 * <p>
 * DeepSeek 的 {@code base-url} 就是完整的 OpenAI 兼容根地址（如 {@code https://api.deepseek.com}），
 * 不需要像百炼那样拼路径，因此这里只做"必填校验 + 去掉末尾多余的斜杠"。
 * 思考模式的相关开关（return-thinking / send-thinking）由父类统一处理。
 */
@Component
public class DeepSeekStreamingChatModelFactory extends AbstractStreamingChatModelFactory {

    @Override
    public AIProviderEnum provider() {
        return AIProviderEnum.DEEPSEEK;
    }

    @Override
    protected String resolveBaseUrl(AiModelProperties.Model properties) {
        if (isUnresolved(properties.getBaseUrl())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "缺少接口地址：请配置 ai.models.<" + properties.getModelName() + ">.base-url 或对应的环境变量");
        }
        String baseUrl = properties.getBaseUrl().trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
