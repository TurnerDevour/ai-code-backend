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
            chatHistoryService.addChatMessage(appId, loginUser.getId(), aiResponse, ChatMessageTypeEnum.AI);
        }).doOnError(error -> {
            String errorMessage = "AI 回复异常: " + error.getMessage();
            log.error(errorMessage, error);
            chatHistoryService.addChatMessage(appId, loginUser.getId(), errorMessage, ChatMessageTypeEnum.AI);
        });
    }
}
