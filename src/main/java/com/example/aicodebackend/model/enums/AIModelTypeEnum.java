package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * AI 模型类型枚举
 */
@Getter
public enum AIModelTypeEnum {

    DEEPSEEK_FLASH("DeepSeek Flash（速度快）", "deepseek-flash"),
    DEEPSEEK_V4_PRO("DeepSeek V4 Pro（推理强）", "deepseek-v4-pro");

    private final String text;

    private final String value;

    AIModelTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     *
     * @return 枚举值
     */
    public static AIModelTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AIModelTypeEnum anEnum : AIModelTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
