package com.example.aicodebackend.ai.model.message;

import lombok.Getter;

/**
 * 流式消息类型枚举
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI响应"),
    /**
     * AI 思考过程（推理模型的 reasoning_content）
     * <p>
     * 单独一种类型，而不是混进 {@link #AI_RESPONSE}：思考过程要单独累积、单独落库、单独展示，
     * 混在一起会让"对话正文"里夹着大段推理内容（历史、记忆、预览全是噪音）。
     */
    AI_THINKING("ai_thinking", "AI思考过程"),
    TOOL_REQUEST("tool_request", "工具请求"),
    TOOL_EXECUTED("tool_executed", "工具执行结果"),
    ERROR("error", "错误消息");

    private final String value;
    private final String text;

    StreamMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据值获取枚举
     */
    public static StreamMessageTypeEnum getEnumByValue(String value) {
        for (StreamMessageTypeEnum typeEnum : values()) {
            if (typeEnum.getValue().equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
