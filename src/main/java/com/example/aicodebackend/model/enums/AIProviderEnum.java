package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * AI 模型平台（服务商）枚举
 * 同一个模型标识（{@link AIModelTypeEnum}）可能来自不同平台，而不同平台的接口约定并不一致。
 * 目前项目里的四个模型（deepseek-v4.1-flash / deepseek-v4-pro / qwen3.8-max / qwen3.8-flash）……
 */
@Getter
public enum AIProviderEnum {

    BAILIAN("阿里云百炼", "bailian");

    /** 展示名称 */
    private final String text;

    /** 配置里使用的值（对应 ai.models.*.provider） */
    private final String value;

    AIProviderEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /** 根据 value 获取枚举（忽略大小写与首尾空格，配置里手写 BAILIAN 之类也能认出来） */
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
