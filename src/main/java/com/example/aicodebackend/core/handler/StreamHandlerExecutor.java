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
 * 根据代码生成类型创建合适的流处理器：
 * 1. 传统的 Flux<String> 流（HTML、MULTI_FILE） -> SimpleTextStreamHandler
 * 2. TokenStream 格式的复杂流（VUE_PROJECT） -> JsonMessageStreamHandler
 * <p>
 * 另外，HTML / MULTI_FILE 的代码生成结束后在这里统一刷新应用的 {@code edit_time}：
 * 它表示"代码已经和线上部署的不一样了"，用户因此可以重新部署（VUE_PROJECT 在生成任务收尾时刷新）。
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @Resource
    private AppCodeStateService appCodeStateService;

    /**
     * 创建流处理器并处理聊天历史记录
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的流
     */
    public Flux<String> doExecute(Flux<String> originFlux, ChatHistoryService chatHistoryService, long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        Flux<String> handled = switch (codeGenType) {
            case VUE_PROJECT -> // 使用注入的组件实例
                    jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
            case HTML, MULTI_FILE -> // 简单文本处理器不需要依赖注入
                    new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, loginUser);
        };
        return handled.doOnComplete(() -> appCodeStateService.markCodeChanged(appId));
    }
}
