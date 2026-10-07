package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "新一轮开始前修复对话记忆"的装配测试
 * <p>
 * 背景（实测死循环）：工具消息清洗曾经挂在记忆<b>读取</b>路径上。LangChain4j 的工具循环是
 * "先写 assistant(tool_calls) → 执行工具 → 再写工具结果"，而每次写记忆前都会先读一遍存储，
 * 于是刚写进去的 assistant 消息被当成"未完成"删掉、随后的工具结果又被当成"孤立结果"删掉，
 * 模型永远看不到工具结果，以为文件没写成功，反复重写同一批文件 —— Vue 工程模式的生成永不收敛。
 * <p>
 * 修复后清洗只在"新的一轮开始"这个边界做一次（{@link AiCodeGeneratorServiceFactory#getAiCodeGeneratorService}），
 * 本用例锁住这条装配线：脏数据要清掉、完整的工具轮次不能动、清洗失败不能影响生成。
 */
class AiCodeGeneratorServiceMemoryRepairTest {

    /** 只用于占位的服务实现（本用例不调用模型） */
    private static final AiCodeGeneratorService DUMMY = new AiCodeGeneratorService() {
        @Override
        public Flux<String> generateHTMLCodeStream(String prompt) {
            return Flux.empty();
        }

        @Override
        public Flux<String> generateMultipleFileCodeStream(String prompt) {
            return Flux.empty();
        }

        @Override
        public dev.langchain4j.service.TokenStream generateVueProjectCodeStream(long appId, String prompt) {
            return null;
        }

        @Override
        public Flux<String> repairGeneratedCodeStream(String prompt) {
            return Flux.empty();
        }
    };

    /**
     * 测试用工厂：跳过真实的 AiServices 构建（不起模型、不连数据库），只保留"轮次边界修复"这段装配逻辑
     */
    private static class RepairingFactory extends AiCodeGeneratorServiceFactory {

        RepairingFactory(ChatMemoryStore store) {
            super(null, null, null, store, null);
        }

        @Override
        protected AiCodeGeneratorService createAiCodeGeneratorService(long appId,
                                                                      CodeGenTypeEnum codeGenTypeEnum,
                                                                      AIModelTypeEnum aiModelTypeEnum) {
            return DUMMY;
        }
    }

    /** 内存版存储：记录写入次数，便于断言"清理结果被写回" */
    private static class RecordingStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new ConcurrentHashMap<>();
        private int updateCount = 0;
        private boolean failOnRead = false;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            if (failOnRead) {
                throw new IllegalStateException("模拟存储读取失败");
            }
            return new ArrayList<>(data.getOrDefault(memoryId, List.of()));
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            updateCount++;
            data.put(memoryId, new ArrayList<>(messages));
        }

        @Override
        public void deleteMessages(Object memoryId) {
            data.remove(memoryId);
        }
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

    /** 上一轮异常中断留下的不完整工具上下文：新一轮开始前必须清掉并写回 */
    @Test
    void shouldRepairStoredMemoryWhenNewRoundStarts() {
        RecordingStore store = new RecordingStore();
        AiMessage abandoned = aiWithToolCall("call_lost");
        store.updateMessages(42L, new ArrayList<>(List.of(
                UserMessage.from("做一个企业官网"),
                abandoned)));
        int updatesBefore = store.updateCount;

        AiCodeGeneratorService service = new RepairingFactory(store)
                .getAiCodeGeneratorService(42L, CodeGenTypeEnum.VUE_PROJECT, AIModelTypeEnum.DEEPSEEK_FLASH);

        assertNotNull(service);
        List<ChatMessage> repaired = store.getMessages(42L);
        assertEquals(1, repaired.size(), "未完成的工具调用必须被清掉，实际=" + repaired);
        assertTrue(store.updateCount > updatesBefore, "清理结果必须写回存储，否则脏数据会一直留着");
    }

    /** 完整的工具轮次不是脏数据：轮次边界不能动它 */
    @Test
    void shouldKeepCompleteToolRoundWhenNewRoundStarts() {
        RecordingStore store = new RecordingStore();
        store.updateMessages(43L, new ArrayList<>(List.of(
                UserMessage.from("做一个企业官网"),
                aiWithToolCall("call_1"),
                ToolExecutionResultMessage.from("call_1", "writeToFile", "文件写入成功"))));

        new RepairingFactory(store)
                .getAiCodeGeneratorService(43L, CodeGenTypeEnum.VUE_PROJECT, AIModelTypeEnum.DEEPSEEK_FLASH);

        assertEquals(3, store.getMessages(43L).size(), "完整的工具轮次必须原样保留");
    }

    /** 存储不可用时只记日志：不能因为"自愈失败"就让用户的生成请求失败 */
    @Test
    void shouldStillReturnServiceWhenRepairFails() {
        RecordingStore store = new RecordingStore();
        store.failOnRead = true;

        AiCodeGeneratorService service = new RepairingFactory(store)
                .getAiCodeGeneratorService(44L, CodeGenTypeEnum.HTML, AIModelTypeEnum.DEEPSEEK_FLASH);

        assertNotNull(service, "记忆自愈失败不应影响服务获取");
    }
}
