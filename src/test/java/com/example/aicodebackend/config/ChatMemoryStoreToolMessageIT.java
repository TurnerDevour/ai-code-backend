package com.example.aicodebackend.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实存储（Redis）上的工具消息行为集成测试
 * <p>
 * 需要本机 Redis，因此按仓库惯例用 {@code *IT} 命名（不随 {@code mvn test} 跑）；
 * 手动验证：{@code mvn -o test "-Dtest=ChatMemoryStoreToolMessageIT"}。
 * <p>
 * 两个关键约定：
 * <ol>
 *     <li><b>读取路径必须原样返回</b>：LangChain4j 的工具循环是"先写 assistant(tool_calls)，
 *     执行完工具再写结果"，而每次写记忆前都会先读一遍存储。读取时若把"有 tool_calls、没有结果"
 *     当成脏数据清掉，正在进行的工具轮次会连同结果一起消失，模型永远看不到工具结果、
 *     反复重写同一批文件（Vue 工程模式的死循环就是这么来的）；</li>
 *     <li><b>脏数据只在轮次边界清理</b>：上一轮异常中断留下的残留由
 *     {@link ChatMemorySanitizer#repairStoredMessages} 在新一轮开始前清掉，
 *     否则该应用之后每次请求都会被接口拒绝（insufficient tool messages following tool_calls message）。</li>
 * </ol>
 */
@SpringBootTest
class ChatMemoryStoreToolMessageIT {

    /** 专用测试记忆 id，用例结束后删除，不污染真实应用数据 */
    private static final long TEST_MEMORY_ID = 990000000000009001L;

    @Autowired
    private ChatMemoryStore chatMemoryStore;

    @AfterEach
    void tearDown() {
        chatMemoryStore.deleteMessages(TEST_MEMORY_ID);
    }

    private static AiMessage aiWithToolCall(String toolCallId) {
        return AiMessage.builder()
                .text("写入文件")
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id(toolCallId)
                        .name("writeToFile")
                        .arguments("{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template/>\"}")
                        .build()))
                .build();
    }

    /**
     * 死循环回归用例：模拟 LangChain4j 工具轮次的真实读写时序，
     * 模型必须能在下一轮请求里看到"工具调用 + 工具结果"
     */
    @Test
    void inFlightToolRoundMustSurviveMemoryReads() {
        chatMemoryStore.deleteMessages(TEST_MEMORY_ID);
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(TEST_MEMORY_ID)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(50)
                .build();

        memory.add(SystemMessage.from("系统提示"));
        memory.add(UserMessage.from("做一个企业官网"));
        // 1) 模型先发出工具调用（此刻还没有结果，这是"正在进行中"的正常状态）
        memory.add(aiWithToolCall("call_1"));
        List<ChatMessage> afterToolCall = memory.messages();
        assertTrue(containsToolCall(afterToolCall, "call_1"),
                "读取路径不能删掉正在进行的工具调用，实际=" + afterToolCall);
        // 2) 工具执行完成后写回结果
        memory.add(ToolExecutionResultMessage.from("call_1", "writeToFile", "文件写入成功"));
        List<ChatMessage> afterResult = memory.messages();
        assertTrue(containsToolCall(afterResult, "call_1"), "工具调用必须还在，实际=" + afterResult);
        assertTrue(containsToolResult(afterResult, "call_1"),
                "工具结果必须能被模型看到，否则模型会反复重写同一个文件，实际=" + afterResult);
    }

    /** 轮次边界修复：不完整的工具上下文会被清掉，且只清一次 */
    @Test
    void abandonedToolRoundShouldBeRepairedAtRoundBoundary() {
        chatMemoryStore.deleteMessages(TEST_MEMORY_ID);
        chatMemoryStore.updateMessages(TEST_MEMORY_ID, new ArrayList<>(List.of(
                UserMessage.from("做一个企业官网"),
                aiWithToolCall("call_abandoned"))));

        int removed = ChatMemorySanitizer.repairStoredMessages(chatMemoryStore, TEST_MEMORY_ID);

        assertEquals(1, removed, "上一轮残留的未完成工具调用应被清掉");
        List<ChatMessage> repaired = chatMemoryStore.getMessages(TEST_MEMORY_ID);
        assertFalse(containsToolCall(repaired, "call_abandoned"), "清理结果必须写回存储");
        assertEquals(0, ChatMemorySanitizer.repairStoredMessages(chatMemoryStore, TEST_MEMORY_ID), "修复必须幂等");
    }

    private static boolean containsToolCall(List<ChatMessage> messages, String toolCallId) {
        return messages.stream()
                .filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast)
                .flatMap(ai -> ai.toolExecutionRequests().stream())
                .anyMatch(request -> request.id().equals(toolCallId));
    }

    private static boolean containsToolResult(List<ChatMessage> messages, String toolCallId) {
        return messages.stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .anyMatch(result -> result.id().equals(toolCallId));
    }
}
