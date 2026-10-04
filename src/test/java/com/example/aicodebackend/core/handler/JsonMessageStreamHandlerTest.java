package com.example.aicodebackend.core.handler;

import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.ChatHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具写入内容落库的回归测试
 * <p>
 * 推送给前端和落库的内容都必须保持完整，保证用户「查看对话」时能看到完整历史；
 * 控制模型上下文的压缩发生在加载对话记忆时（见 TextUtilsTest）。
 */
class JsonMessageStreamHandlerTest {

    private static final int CONTENT_LENGTH = 5000;

    @Test
    @DisplayName("工具写入大文件：前端与落库内容都保持完整")
    void shouldKeepFullContentForClientAndHistory() {
        String content = "A".repeat(CONTENT_LENGTH);
        String arguments = JSONUtil.createObj()
                .set("relativePath", "src/App.vue")
                .set("content", content)
                .toString();
        String chunk = JSONUtil.createObj()
                .set("type", "tool_executed")
                .set("id", "1")
                .set("name", "writeToFile")
                .set("arguments", arguments)
                .set("result", "写入成功")
                .toString();

        List<String> persistedMessages = new ArrayList<>();
        List<String> pushedMessages = new JsonMessageStreamHandler()
                .handle(Flux.just(chunk), stubChatHistoryService(persistedMessages), 1L, new User())
                .collectList()
                .block();

        assertNotNull(pushedMessages, "推送给前端的消息列表不应为空");
        assertEquals(1, pushedMessages.size(), "应向前端下发一条消息");
        assertTrue(pushedMessages.get(0).contains(content), "推送给前端的内容应保持完整");

        assertEquals(1, persistedMessages.size(), "应落库一条 AI 消息");
        String persistedMessage = persistedMessages.get(0);
        assertTrue(persistedMessage.contains("src/App.vue"), "落库内容应保留文件路径");
        assertTrue(persistedMessage.contains(content), "落库内容应保持完整，保证「查看对话」能看到完整历史");
        assertFalse(persistedMessage.contains("已省略"), "落库内容不应出现截断说明");
    }

    /**
     * 手写 ChatHistoryService 桩对象，仅捕获落库的消息内容
     * <p>
     * 这里用 JDK 动态代理而不是 Mockito —— 当前运行环境下 byte-buddy 无法初始化 MockMaker。
     *
     * @param persistedMessages 用于收集落库消息的列表
     */
    private ChatHistoryService stubChatHistoryService(List<String> persistedMessages) {
        return (ChatHistoryService) Proxy.newProxyInstance(
                ChatHistoryService.class.getClassLoader(),
                new Class<?>[]{ChatHistoryService.class},
                (proxy, method, args) -> {
                    if ("addChatMessage".equals(method.getName()) && args != null && args.length >= 3) {
                        persistedMessages.add(String.valueOf(args[2]));
                        return 1L;
                    }
                    return switch (method.getName()) {
                        case "toString" -> "StubChatHistoryService";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
    }
}
