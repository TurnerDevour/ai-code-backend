package com.example.aicodebackend.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话记忆工具消息清洗的单元测试
 * <p>
 * 用例来自线上实测的损坏记忆：{@code AiMessage} 带 1 个 tool_call 但后面没有任何结果消息，
 * 导致该应用之后每次对话都被接口拒绝（insufficient tool messages following tool_calls message）。
 */
class ChatMemorySanitizerTest {

    private static AiMessage aiWithTools(String... toolCallIds) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (String id : toolCallIds) {
            requests.add(ToolExecutionRequest.builder()
                    .id(id)
                    .name("writeToFile")
                    .arguments("{\"relativePath\":\"a.txt\",\"content\":\"x\"}")
                    .build());
        }
        return AiMessage.builder().text("写入文件").toolExecutionRequests(requests).build();
    }

    private static ToolExecutionResultMessage result(String toolCallId) {
        return ToolExecutionResultMessage.from(toolCallId, "writeToFile", "文件写入成功");
    }

    /** 实测的坏数据：带 tool_call 但没有结果 → 必须被丢弃 */
    @Test
    void shouldDropToolCallWithoutResult() {
        AiMessage orphan = aiWithTools("call_00_dCpyKTRfmyZ789P8avZT7304");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("企业网站"),
                AiMessage.from("生成完毕"),
                UserMessage.from("UI"),
                orphan,
                UserMessage.from("增加可切换主题色功能")));

        List<ChatMessage> sanitized = ChatMemorySanitizer.sanitizeToolMessages(messages);

        assertEquals(5, sanitized.size(), "只应丢弃那条未完成的 assistant 消息");
        assertFalse(sanitized.contains(orphan));
        assertEquals("增加可切换主题色功能", ((UserMessage) sanitized.get(4)).singleText());
        // 清洗后不再存在任何"带 tool_calls 却没有结果"的消息
        assertFalse(hasDanglingToolCall(sanitized));
    }

    /** 完整的工具调用与结果必须原样保留 */
    @Test
    void shouldKeepCompleteToolPairs() {
        AiMessage ai = aiWithTools("call_1", "call_2");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("写两个文件"),
                ai,
                result("call_1"),
                result("call_2"),
                AiMessage.from("写好了")));

        List<ChatMessage> sanitized = ChatMemorySanitizer.sanitizeToolMessages(messages);

        assertEquals(5, sanitized.size());
        assertTrue(sanitized.contains(ai));
    }

    /** 结果数量少于请求数量（部分执行）也要丢弃，否则接口同样会拒绝 */
    @Test
    void shouldDropPartiallyCompletedToolCalls() {
        AiMessage ai = aiWithTools("call_1", "call_2");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("写两个文件"),
                ai,
                result("call_1"),
                AiMessage.from("继续")));

        List<ChatMessage> sanitized = ChatMemorySanitizer.sanitizeToolMessages(messages);

        assertEquals(2, sanitized.size(), "assistant + 半截结果都应被移除");
        assertEquals("写两个文件", ((UserMessage) sanitized.get(0)).singleText());
        assertEquals("继续", ((AiMessage) sanitized.get(1)).text());
    }

    /** 孤立的工具结果（找不到对应请求）同样要丢弃 */
    @Test
    void shouldDropOrphanToolResult() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("你好"),
                result("call_lost"),
                AiMessage.from("你好")));

        List<ChatMessage> sanitized = ChatMemorySanitizer.sanitizeToolMessages(messages);

        assertEquals(2, sanitized.size());
        assertInstanceOf(UserMessage.class, sanitized.get(0));
        assertInstanceOf(AiMessage.class, sanitized.get(1));
    }

    /** 空列表 / null 不应抛异常 */
    @Test
    void shouldHandleEmptyInput() {
        assertTrue(ChatMemorySanitizer.sanitizeToolMessages(null).isEmpty());
        assertTrue(ChatMemorySanitizer.sanitizeToolMessages(List.of()).isEmpty());
    }

    /**
     * 轮次边界修复：上一轮异常中断留下的"有 tool_calls、没有结果"必须被清掉并写回
     * <p>
     * 这是 {@link #shouldDropToolCallWithoutResult} 的"落库版本"——只有在新一轮开始前做才是安全的。
     */
    @Test
    void shouldRepairAbandonedToolRoundAtRoundBoundary() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        AiMessage abandoned = aiWithTools("call_lost");
        store.updateMessages(7L, new ArrayList<>(List.of(
                UserMessage.from("做一个企业站"),
                abandoned,
                AiMessage.from("（上一轮被打断）"))));

        int removed = ChatMemorySanitizer.repairStoredMessages(store, 7L);

        assertEquals(1, removed, "未完成的工具调用必须被清掉");
        assertFalse(store.getMessages(7L).contains(abandoned));
        assertEquals(0, ChatMemorySanitizer.repairStoredMessages(store, 7L), "修复必须是幂等的");
    }

    /** 完整的工具轮次不属于脏数据，轮次边界修复不能动它 */
    @Test
    void shouldNotRepairCompleteToolRound() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        AiMessage ai = aiWithTools("call_1");
        List<ChatMessage> complete = new ArrayList<>(List.of(
                UserMessage.from("写一个文件"),
                ai,
                result("call_1"),
                AiMessage.from("写好了")));
        store.updateMessages(8L, complete);

        int removed = ChatMemorySanitizer.repairStoredMessages(store, 8L);

        assertEquals(0, removed);
        assertEquals(complete.size(), store.getMessages(8L).size());
    }

    /** 空存储 / 非法参数不应抛异常 */
    @Test
    void shouldRepairSafelyOnEmptyOrInvalidInput() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        assertEquals(0, ChatMemorySanitizer.repairStoredMessages(store, 9L));
        assertEquals(0, ChatMemorySanitizer.repairStoredMessages(null, 9L));
        assertEquals(0, ChatMemorySanitizer.repairStoredMessages(store, null));
    }

    /**
     * 内存版记忆存储：只做读写、不做任何清洗
     * <p>
     * 与真实存储（{@code JedisChatMemoryStore}）的读取语义保持一致：
     * 读取路径必须是"原样返回"，任何清洗都只能在轮次边界显式触发。
     */
    private static class InMemoryChatMemoryStore implements ChatMemoryStore {

        private final java.util.Map<Object, List<ChatMessage>> data = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            data.put(memoryId, new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }
    }

    /** 窗口裁剪：SystemMessage 固定首位，淘汰 assistant 时其工具结果一并淘汰 */
    @Test
    void shouldTrimWindowWithoutLeavingDanglingResults() {
        AiMessage ai = aiWithTools("call_1");
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("系统提示"),
                UserMessage.from("u1"),
                ai,
                result("call_1"),
                UserMessage.from("u2"),
                AiMessage.from("a2")));

        List<ChatMessage> trimmed = ChatMemorySanitizer.trimToWindow(messages, 4);

        // 淘汰 assistant 时它的工具结果一起走，因此可能"多淘汰一点"（不会超过上限）
        assertTrue(trimmed.size() <= 4, "裁剪后不应超过窗口上限，实际=" + trimmed.size());
        assertInstanceOf(SystemMessage.class, trimmed.get(0), "系统消息必须保留在首位");
        assertFalse(hasDanglingToolCall(trimmed));
        // 工具结果不能独立存在（它对应的 assistant 已被淘汰）
        assertTrue(trimmed.stream().noneMatch(ToolExecutionResultMessage.class::isInstance));
    }

    /** 未超窗口时不改动 */
    @Test
    void shouldNotTrimWhenWithinWindow() {
        List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("a"), AiMessage.from("b")));
        assertEquals(messages, ChatMemorySanitizer.trimToWindow(messages, 10));
    }

    /**
     * 判断消息列表里是否还有"带 tool_calls 却没有结果"的消息
     */
    private static boolean hasDanglingToolCall(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                int results = 0;
                int next = i + 1;
                while (next < messages.size() && messages.get(next) instanceof ToolExecutionResultMessage) {
                    results++;
                    next++;
                }
                if (results != ai.toolExecutionRequests().size()) {
                    return true;
                }
            }
        }
        return false;
    }
}
