package com.example.aicodebackend.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 针对 Redis 记忆读取路径的集成测试
 * <p>
 * 这里直接读取真实存储（应用配置里的 Redis），验证
 * {@link JedisChatMemoryStore#getMessages(Object)} 返回的记忆里不会存在
 * "带 tool_calls 却没有对应工具结果"的消息——这正是导致该应用每次对话都被接口拒绝的原因。
 * <p>
 * 清洗结果会写回存储，因此这个用例同时也修复了本地遗留的脏数据。
 */
@SpringBootTest
class ChatMemoryStoreSanitizeIT {

    /** 实测被未完成工具调用污染的应用（本机数据；不存在时用例自动跳过） */
    private static final String POLLUTED_APP_ID = "465089248937918464";
    /** 对照：未受污染的应用 */
    private static final String HEALTHY_APP_ID = "465089256215035904";

    @Autowired
    private ChatMemoryStore chatMemoryStore;

    @Test
    void storeShouldNeverReturnDanglingToolCalls() {
        for (String appId : new String[]{POLLUTED_APP_ID, HEALTHY_APP_ID}) {
            List<ChatMessage> messages = chatMemoryStore.getMessages(appId);
            if (messages.isEmpty()) {
                System.out.println("appId=" + appId + " 无记忆数据，跳过");
                continue;
            }
            Set<String> pending = findDanglingToolCalls(messages);
            assertFalse(!pending.isEmpty(),
                    "appId=" + appId + " 仍存在未配对的工具调用（会导致接口拒绝）：" + pending);
            System.out.println("appId=" + appId + " 记忆条数=" + messages.size() + "，工具调用配对检查通过");
        }
    }

    @Test
    void sanitizedMemoryShouldStayCleanWhenReadAgain() {
        List<ChatMessage> first = chatMemoryStore.getMessages(POLLUTED_APP_ID);
        if (first.isEmpty()) {
            return;
        }
        // 第二次读取（此时第一次读取已把清洗结果写回）也必须是干净的
        List<ChatMessage> second = chatMemoryStore.getMessages(POLLUTED_APP_ID);
        assertNotNull(second);
        assertFalse(!findDanglingToolCalls(second).isEmpty(), "写回后的记忆应保持干净");
        System.out.println("二次读取条数：" + first.size() + " -> " + second.size());
    }

    /**
     * 找出所有"带 tool_calls 但没有完整工具结果"的 assistant 消息，返回其 toolCallId
     *
     * @param messages 消息列表
     *
     * @return 未配对的 toolCallId 集合
     */
    private static Set<String> findDanglingToolCalls(List<ChatMessage> messages) {
        Set<String> dangling = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
                continue;
            }
            Set<String> resultIds = new HashSet<>();
            int next = i + 1;
            while (next < messages.size() && messages.get(next) instanceof ToolExecutionResultMessage result) {
                resultIds.add(result.id());
                next++;
            }
            ai.toolExecutionRequests().stream()
                    .map(request -> request.id())
                    .filter(id -> !resultIds.contains(id))
                    .forEach(dangling::add);
        }
        return dangling;
    }
}
