package com.example.aicodebackend.core.handler;

import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.tools.BaseTool;
import com.example.aicodebackend.ai.tools.FileWriteTool;
import com.example.aicodebackend.ai.tools.ToolManager;
import com.example.aicodebackend.ai.tools.ToolMessageRenderer;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.ChatHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用消息处理的回归测试
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
        List<String> pushedMessages = createHandler()
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

    @Test
    @DisplayName("工具请求：由 ToolManager 取到的工具自己生成展示内容，同一个工具 ID 只展示一次")
    void shouldDelegateToolRequestToToolManager() {
        String toolRequest = JSONUtil.createObj()
                .set("type", "tool_request")
                .set("id", "call_1")
                .set("name", "writeToFile")
                .set("arguments", "{\"relativeFilePath\":\"src/App.vue\"}")
                .toString();

        List<String> pushedMessages = createHandler()
                .handle(Flux.just(toolRequest, toolRequest), stubChatHistoryService(new ArrayList<>()), 1L, new User())
                .collectList()
                .block();

        assertNotNull(pushedMessages, "推送给前端的消息列表不应为空");
        assertEquals(1, pushedMessages.size(), "同一个工具 ID 重复请求只应展示一次");
        assertTrue(pushedMessages.get(0).contains("写入文件内容"), "应展示工具自己定义的中文名称");
    }

    @Test
    @DisplayName("未注册的工具：跳过展示，不影响 AI 文本输出")
    void shouldSkipUnregisteredTool() {
        String aiResponse = JSONUtil.createObj()
                .set("type", "ai_response")
                .set("data", "开始生成")
                .toString();
        String unknownToolRequest = JSONUtil.createObj()
                .set("type", "tool_request")
                .set("id", "call_1")
                .set("name", "notExistTool")
                .set("arguments", "{}")
                .toString();

        List<String> pushedMessages = createHandler()
                .handle(Flux.just(unknownToolRequest, aiResponse), stubChatHistoryService(new ArrayList<>()), 1L, new User())
                .collectList()
                .block();

        assertNotNull(pushedMessages, "推送给前端的消息列表不应为空");
        assertEquals(List.of("开始生成"), pushedMessages, "未注册的工具不产生任何输出");
    }

    /**
     * HTML / 多文件模式：思考过程原样下发给前端，同时单独落库到 {@code chat_history.thinking}
     * <p>
     * 回归背景（实测）：这两个模式以前用 {@code Flux<String>} 接模型输出，思考内容在 langchain4j 的
     * Reactor 适配层就被丢掉了；改成 TokenStream + 消息流之后，必须保证：
     * 思考走 {@code ai_thinking} 消息下发（前端才有思考面板），正文里不含推理内容，且思考单独一列落库。
     */
    @Test
    @DisplayName("HTML/多文件：思考过程单列落库，正文与思考互不污染")
    void shouldPersistThinkingSeparatelyFromContent() {
        String thinking = JSONUtil.createObj().set("type", "ai_thinking").set("data", "先想布局").toString();
        String secondThinking = JSONUtil.createObj().set("type", "ai_thinking").set("data", "再想配色").toString();
        String content = JSONUtil.createObj().set("type", "ai_response").set("data", "```html<body>ok</body>```").toString();

        List<String> persistedMessages = new ArrayList<>();
        List<String> persistedThinking = new ArrayList<>();
        List<String> pushedMessages = createHandler()
                .handleGeneratedFiles(Flux.just(thinking, content, secondThinking),
                        stubChatHistoryService(persistedMessages, persistedThinking), 1L, new User())
                .collectList()
                .block();

        assertNotNull(pushedMessages);
        assertEquals(3, pushedMessages.size(), "思考与正文都要下发给前端");
        assertTrue(pushedMessages.contains(thinking), "思考消息要原样透传（前端按 type 渲染思考面板）");
        assertTrue(pushedMessages.contains("```html<body>ok</body>```"), "正文仍按文本增量下发，展示不受影响");

        assertEquals(1, persistedMessages.size(), "只落库一条 AI 消息");
        assertEquals("```html<body>ok</body>```", persistedMessages.get(0), "正文里不能夹着推理内容");
        assertEquals(List.of("先想布局再想配色"), persistedThinking, "思考过程应按到达顺序单独累积");
    }

    /**
     * 构建被测处理器，并按 ToolMessageRenderer 的职责注入它需要的工具实例
     * <p>
     * 工具实例在容器外无法自动装配，这里手动设置 ToolManager 的注入字段，
     * 再把它交给 ToolMessageRenderer（工具展示文案统一由它生成）。
     */
    private JsonMessageStreamHandler createHandler() {
        ToolManager toolManager = new ToolManager();
        ReflectionTestUtils.setField(toolManager, "tools", new BaseTool[]{new FileWriteTool()});
        toolManager.initTools();

        JsonMessageStreamHandler handler = new JsonMessageStreamHandler();
        ReflectionTestUtils.setField(handler, "toolMessageRenderer", new ToolMessageRenderer(toolManager));
        return handler;
    }

    /**
     * 手写 ChatHistoryService 桩对象，仅捕获落库的消息内容
     * <p>
     * 这里用 JDK 动态代理而不是 Mockito —— 当前运行环境下 byte-buddy 无法初始化 MockMaker。
     *
     * @param persistedMessages 用于收集落库消息的列表
     */
    private ChatHistoryService stubChatHistoryService(List<String> persistedMessages) {
        return stubChatHistoryService(persistedMessages, null);
    }

    /**
     * 手写 ChatHistoryService 桩对象，捕获落库的正文与思考过程
     * <p>
     * 思考过程走 {@link ChatHistoryService#addAiChatMessage} 单独一列，正文走
     * {@link ChatHistoryService#addChatMessage}（无思考时）。
     *
     * @param persistedMessages 用于收集落库消息（正文）的列表
     * @param persistedThinking 用于收集思考过程的列表，为 null 时不关心
     */
    private ChatHistoryService stubChatHistoryService(List<String> persistedMessages, List<String> persistedThinking) {
        return (ChatHistoryService) Proxy.newProxyInstance(
                ChatHistoryService.class.getClassLoader(),
                new Class<?>[]{ChatHistoryService.class},
                (proxy, method, args) -> {
                    if ("addChatMessage".equals(method.getName()) && args != null && args.length >= 3) {
                        persistedMessages.add(String.valueOf(args[2]));
                        return 1L;
                    }
                    if ("addAiChatMessage".equals(method.getName()) && args != null && args.length >= 4) {
                        persistedMessages.add(String.valueOf(args[2]));
                        if (persistedThinking != null) {
                            persistedThinking.add(String.valueOf(args[3]));
                        }
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
