package com.example.aicodebackend.core.handler;

import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.service.AppCodeStateService;
import com.example.aicodebackend.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器
 * 根据代码生成类型创建合适的流处理器（两类流都是"正文 / 思考 / 工具"的 JSON 消息流，
 * 差别只在收尾动作与思考过程的落库位置）……
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private AppCodeStateService appCodeStateService;

    /** 创建流处理器并处理聊天历史记录 */
    public Flux<String> doExecute(Flux<String> originFlux, ChatHistoryService chatHistoryService, long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        Flux<String> handled = switch (codeGenType) {
            case VUE_PROJECT -> // 使用注入的组件实例
                    jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
            case HTML, MULTI_FILE ->
                    jsonMessageStreamHandler.handleGeneratedFiles(originFlux, chatHistoryService, appId, loginUser);
        };
        return handled.doOnComplete(() -> appCodeStateService.markCodeChanged(appId));
    }
}
