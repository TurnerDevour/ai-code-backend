package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * AI 模型类型枚举
 * <p>
 * {@code value} 是唯一的模型标识，同时被三个地方复用：
 * <ol>
 *     <li>创建应用 / 对话接口里的 {@code aiModelType} 请求参数；</li>
 *     <li>数据库 {@code app.ai_model_type} 字段的取值；</li>
 *     <li>配置文件中 {@code ai.models.<value>} 的 key（由 {@code StreamingChatModelRegistry} 按此对应关系取模型）。</li>
 * </ol>
 * 因此这里的 value 必须与配置文件里的 key 完全一致；新增模型时两处都要加，
 * 缺少配置的模型不会阻止应用启动，只会在真正被选中时报错（见 {@code StreamingChatModelRegistry}）。
 */
@Getter
public enum AIModelTypeEnum {

    DEEPSEEK_FLASH("DeepSeek Flash（速度快）", "deepseek-flash"),
    DEEPSEEK_V4_PRO("DeepSeek V4 Pro（推理强）", "deepseek-v4-pro"),
    /**
     * 阿里云百炼旗舰模型：100 万上下文、默认思考模式，支持 function-calling
     */
    QWEN_3_8_MAX("Qwen3.8-Max（阿里云百炼旗舰）", "qwen3.8-max"),
    /**
     * 阿里云百炼高性价比模型：同样支持思考模式与 function-calling，单价明显更低
     */
    QWEN_3_7_PLUS("Qwen3.7-Plus（阿里云百炼高性价比）", "qwen3.7-plus");

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
