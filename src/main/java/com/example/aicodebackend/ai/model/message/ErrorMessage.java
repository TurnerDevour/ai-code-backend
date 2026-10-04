package com.example.aicodebackend.ai.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 错误消息
 * <p>
 * 流式响应出错时，以该消息体作为数据帧下发给前端。
 * 之所以用数据帧而不是抛出异常：此时响应已按 text/event-stream 提交，
 * 异常逃逸到 Servlet 层后 @RestControllerAdvice 无法再回写 JSON 结构的错误体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ErrorMessage extends StreamMessage {

    /**
     * 错误提示信息
     */
    private String data;

    public ErrorMessage(String data) {
        super(StreamMessageTypeEnum.ERROR.getValue());
        this.data = data;
    }
}
