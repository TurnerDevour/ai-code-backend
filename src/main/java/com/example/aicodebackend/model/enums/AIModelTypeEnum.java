package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * AI 模型类型枚举
 * {@code value} 是唯一的模型标识，同时被三个地方复用：
 * 因此这里的 value 必须与配置文件里的 key 完全一致；新增模型时两处都要加……
 */
@Getter
public enum AIModelTypeEnum {

    /** 阿里云百炼（DeepSeek 系列）：速度快，适合多文件 HTML 生成 */
    DEEPSEEK_V4_1_FLASH("DeepSeek V4.1-Flash（阿里云百炼，速度快）", "deepseek-v4.1-flash"),
    /** 阿里云百炼（DeepSeek 系列）：编程、数学与通用推理最强 */
    DEEPSEEK_V4_PRO("DeepSeek V4 Pro（阿里云百炼，推理强）", "deepseek-v4-pro"),
    /** 阿里云百炼旗舰模型：100 万上下文、默认思考模式，支持 function-calling */
    QWEN_3_8_MAX("Qwen3.8-Max（阿里云百炼旗舰）", "qwen3.8-max"),
    /** 阿里云百炼高性价比模型：同样支持思考模式与 function-calling，单价明显更低 */
    QWEN_3_8_FLASH("Qwen3.8-Flash（阿里云百炼高性价比）", "qwen3.8-flash");

    private final String text;

    private final String value;

    /** 未显式指定模型时的默认模型 */
    public static final AIModelTypeEnum DEFAULT_MODEL = DEEPSEEK_V4_1_FLASH;

    AIModelTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /** 根据 value 获取枚举 */
    public static AIModelTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (AIModelTypeEnum anEnum : values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
