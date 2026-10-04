package com.example.aicodebackend.core.handler;

import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * SimpleTextStreamHandler 是一个简单的文本流处理器类，
 * <br/>
 * 用于处理HTML 和 MULTI_FILE 类型的流数据。
 */
@Slf4j
public class SimpleTextStreamHandler {

    public Flux<String> handle(Flux<String> originFlux, ChatHistoryService chatHistoryService, long appId, User loginUser) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        return originFlux.map(chunk -> {
            aiResponseBuilder.append(chunk);
            return chunk;
        }).doOnComplete(() -> {
            String aiResponse = aiResponseBuilder.toString();
            try {
                chatHistoryService.addChatMessage(appId, loginUser.getId(), aiResponse, ChatMessageTypeEnum.AI);
            } catch (Exception e) {
                // 此时 SSE 响应已进入完成阶段，异常逃逸会让整个请求以 500 收尾，
                // 且响应已按 text/event-stream 提交，Spring 无法再回写业务错误。因此这里只记录日志。
                log.error("保存 AI 回复到对话历史失败，appId: {}", appId, e);
            }
        }).doOnError(error -> {
            String errorMessage = "AI 回复异常: " + error.getMessage();
            log.error(errorMessage, error);
            try {
                chatHistoryService.addChatMessage(appId, loginUser.getId(), errorMessage, ChatMessageTypeEnum.AI);
            } catch (Exception e) {
                log.error("保存 AI 失败消息到对话历史失败，appId: {}", appId, e);
            }
        });
    }
}
