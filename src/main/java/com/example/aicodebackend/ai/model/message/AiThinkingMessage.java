package com.example.aicodebackend.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * AI 思考过程消息（推理模型的 {@code reasoning_content} 增量）
 * <p>
 * 与 {@link AiResponseMessage} 结构一致，但语义不同：它是模型的思考过程，不是要展示给用户的正文。
 * 前端把它放进对话页顶部的「AI 思考过程」面板单独展示，后端把它单独累积进
 * {@code chat_history.thinking} 字段。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class AiThinkingMessage extends StreamMessage {

    private String data;

    public AiThinkingMessage(String data) {
        super(StreamMessageTypeEnum.AI_THINKING.getValue());
        this.data = data;
    }
}
