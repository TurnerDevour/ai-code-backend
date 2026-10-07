package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * AI 模型平台（服务商）枚举
 * <p>
 * 同一个模型标识（{@link AIModelTypeEnum}）可能来自不同平台，而不同平台的接口约定并不一致：
 * <ul>
 *     <li>DeepSeek：{@code base-url} 就是完整的 OpenAI 兼容根路径，直接使用；</li>
 *     <li>阿里云百炼：OpenAI 兼容路径固定是 {@code /compatible-mode/v1}，业务空间（workspace）
 *     体现在域名里，需要拼接与校验，见 {@code BailianEndpointResolver}。</li>
 * </ul>
 * 因此配置里显式声明 {@code provider}，由对应的 {@code StreamingChatModelFactory} 负责构建模型，
 * 新增平台时只需要加一个枚举值 + 一个工厂实现，不必改动注册表与业务代码。
 */
@Getter
public enum AIProviderEnum {

    DEEPSEEK("DeepSeek 开放平台", "deepseek"),

    BAILIAN("阿里云百炼", "bailian");

    /**
     * 展示名称
     */
    private final String text;

    /**
     * 配置里使用的值（对应 ai.models.*.provider）
     */
    private final String value;

    AIProviderEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举（忽略大小写与首尾空格，配置里手写 dEEPseek 之类也能认出来）
     *
     * @param value 枚举值的 value
     *
     * @return 枚举值，未匹配时返回 null
     */
    public static AIProviderEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        String normalized = value.trim();
        for (AIProviderEnum anEnum : AIProviderEnum.values()) {
            if (anEnum.value.equalsIgnoreCase(normalized)) {
                return anEnum;
            }
        }
        return null;
    }
}
