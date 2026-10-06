package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * SimpleTextStreamHandler 是一个简单的文本流处理器类，
 * 用于处理 HTML 和 MULTI_FILE 类型的流数据。
 * <p>
 * 收尾语义（本次修复点）：流可能以三种方式结束——正常完成、出错、被客户端取消（用户点返回/关闭页面/断网）。
 * 前两种沿用原有行为，第三种之前什么都不做，于是"服务端已经生成的内容"与"对话历史"不一致。
 * 现在把收尾统一交给 {@link GenerationCompletionHandler}：它把生成过程与客户端连接解耦，
 * 客户端断开后仍会等生成跑完再落库（内容为空时写入"被中断"占位）。
 */
@Slf4j
public class SimpleTextStreamHandler {

    private final GenerationCompletionHandler completionHandler;

    public SimpleTextStreamHandler() {
        this(null);
    }

    public SimpleTextStreamHandler(GenerationCompletionHandler completionHandler) {
        this.completionHandler = completionHandler == null
                ? new GenerationCompletionHandler(new VueProjectBuilder())
                : completionHandler;
    }

    /**
     * 处理文本流
     *
     * @param originFlux         原始流
     * @param chatHistoryService 对话历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     *
     * @return 下发给客户端的流
     */
    public Flux<String> handle(Flux<String> originFlux, ChatHistoryService chatHistoryService, long appId, User loginUser) {
        return completionHandler.handle(originFlux, chunk -> chunk, null, chatHistoryService, appId, loginUser, false);
    }

    /**
     * 中断标记的包装（保留该类原有的语义入口，便于单测直接构造带标记的内容）
     *
     * @param content 已生成的内容
     *
     * @return 带中断标记的内容
     */
    public static String markInterrupted(String content) {
        return ChatHistoryConstant.INTERRUPTED_MARKER + StrUtil.nullToEmpty(content);
    }
}
