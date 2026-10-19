package com.example.aicodebackend.config;

import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "把数据库历史装进对话记忆"这段装配逻辑的回归测试
 * 背景：这段逻辑原先在 {@code ChatHistoryServiceImpl#loadChatHistoryToMemory} 里……
 */
class AiCodeGeneratorServiceFactoryHistoryTest {

    /** 测试用工厂：只暴露"把历史装进记忆"这一段，不构建真实 AiServices */
    private static final class TestFactory extends AiCodeGeneratorServiceFactory {

        TestFactory(ChatHistoryService chatHistoryService, ChatMemoryStore store) {
            super(null, null, store, chatHistoryService);
        }

        void load(long appId, MessageWindowChatMemory memory) {
            loadHistoryIntoMemory(appId, memory);
        }
    }

    /** 内存版记忆存储（不需要 Redis） */
    private static final class InMemoryStore implements ChatMemoryStore {

        private final Map<Object, List<ChatMessage>> data = new HashMap<>();

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

    private static ChatHistory history(ChatMessageTypeEnum type, String text) {
        ChatHistory history = new ChatHistory();
        history.setMessageType(type.getValue());
        history.setMessage(text);
        return history;
    }

    private static MessageWindowChatMemory memory(ChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id(1L)
                .chatMemoryStore(store)
                .maxMessages(ChatHistoryConstant.MEMORY_MAX_MESSAGES)
                .build();
    }

    @Test
    @DisplayName("按时间正序装载，跳过空消息与 error 消息")
    void shouldLoadInOrderAndSkipNonConversationMessages() {
        ChatHistoryService service = mock(ChatHistoryService.class);
        when(service.loadRecentChatHistory(eq(1L), anyInt())).thenReturn(List.of(
                history(ChatMessageTypeEnum.USER, "做一个个人博客"),
                history(ChatMessageTypeEnum.ERROR, "生成失败，请重试"),
                history(ChatMessageTypeEnum.USER, "   "),
                history(ChatMessageTypeEnum.AI, "已生成页面"),
                history(ChatMessageTypeEnum.AI, "")
        ));
        InMemoryStore store = new InMemoryStore();
        MessageWindowChatMemory memory = memory(store);

        new TestFactory(service, store).load(1L, memory);

        List<ChatMessage> messages = memory.messages();
        assertEquals(2, messages.size(), "只应装载 1 条用户消息 + 1 条 AI 消息，实际=" + messages);
        assertInstanceOf(UserMessage.class, messages.get(0), "第一条必须是最早的用户消息");
        assertEquals("做一个个人博客", ((UserMessage) messages.get(0)).singleText(), "用户消息应原样进入上下文");
        assertInstanceOf(AiMessage.class, messages.get(1));
        assertEquals("已生成页面", ((AiMessage) messages.get(1)).text());
    }

    @Test
    @DisplayName("没有历史时不清空已有记忆（与改造前行为一致）")
    void shouldKeepExistingMemoryWhenNoHistory() {
        ChatHistoryService service = mock(ChatHistoryService.class);
        when(service.loadRecentChatHistory(anyLong(), anyInt())).thenReturn(List.of());
        InMemoryStore store = new InMemoryStore();
        MessageWindowChatMemory memory = memory(store);
        memory.add(UserMessage.from("上一轮留在记忆里的内容"));

        new TestFactory(service, store).load(1L, memory);

        assertEquals(1, memory.messages().size(), "没有历史时不应清空记忆");
        assertEquals("上一轮留在记忆里的内容", ((UserMessage) memory.messages().get(0)).singleText());
    }

    @Test
    @DisplayName("装载条数最终仍受记忆窗口裁剪：留下最近的 N 条")
    void shouldTrimLoadedMessagesToMemoryWindow() {
        int overflow = 10;
        List<ChatHistory> many = new ArrayList<>();
        for (int i = 0; i < ChatHistoryConstant.MEMORY_MAX_MESSAGES + overflow; i++) {
            many.add(history(ChatMessageTypeEnum.USER, "消息-" + i));
        }
        ChatHistoryService service = mock(ChatHistoryService.class);
        when(service.loadRecentChatHistory(anyLong(), anyInt())).thenReturn(many);
        InMemoryStore store = new InMemoryStore();
        MessageWindowChatMemory memory = memory(store);

        new TestFactory(service, store).load(1L, memory);

        List<ChatMessage> messages = memory.messages();
        assertEquals(ChatHistoryConstant.MEMORY_MAX_MESSAGES, messages.size(), "窗口大小必须生效");
        assertEquals("消息-" + (ChatHistoryConstant.MEMORY_MAX_MESSAGES + overflow - 1),
                ((UserMessage) messages.get(messages.size() - 1)).singleText(), "最后一条应是最新的消息");
        assertEquals("消息-" + overflow,
                ((UserMessage) messages.get(0)).singleText(), "最早的那几条应被窗口挤掉");
    }

    @Test
    @DisplayName("AI 历史：工具调用记录改写成中性描述、代码块只留预览、代码块外的说明保留")
    void shouldNeutralizeToolRecordsAndCompressCodeBlocks() {
        String toolRecord = "[工具调用] writeToFile "
                + "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template/>\"}";
        String longCodeBlock = "```html\n" + "<div>很长的页面结构</div>".repeat(200) + "\n```";
        String raw = toolRecord + "\n\n已写入文件，页面代码：\n" + longCodeBlock;

        ChatHistoryService service = mock(ChatHistoryService.class);
        when(service.loadRecentChatHistory(anyLong(), anyInt()))
                .thenReturn(List.of(history(ChatMessageTypeEnum.AI, raw)));
        InMemoryStore store = new InMemoryStore();
        MessageWindowChatMemory memory = memory(store);

        new TestFactory(service, store).load(1L, memory);

        String loaded = ((AiMessage) memory.messages().get(0)).text();
        assertFalse(loaded.contains("[工具调用]"),
                "工具调用记录必须被改写，否则模型会照抄这个格式假装调用工具：" + loaded);
        assertTrue(loaded.contains("系统记录"), "改写后应留下中性描述，实际=" + loaded);
        assertTrue(loaded.contains("已写入文件"), "代码块之外的说明文字必须保留");
        assertTrue(loaded.length() < raw.length(),
                "代码块应被压缩（原长 " + raw.length() + "，压缩后 " + loaded.length() + "）");
    }

    @Test
    @DisplayName("记忆存储故障不阻断生成：装载失败按\"没有历史\"继续")
    void shouldNotFailGenerationWhenMemoryStoreFails() {
        ChatHistoryService service = mock(ChatHistoryService.class);
        when(service.loadRecentChatHistory(anyLong(), anyInt()))
                .thenReturn(List.of(history(ChatMessageTypeEnum.USER, "做一个个人博客")));
        // clear() 成功、add() 失败：模拟 Redis 在写入时抖动
        ChatMemoryStore brokenStore = new ChatMemoryStore() {
            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                return List.of();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                throw new IllegalStateException("模拟 Redis 写入失败");
            }

            @Override
            public void deleteMessages(Object memoryId) {
                // 清理成功
            }
        };
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(1L)
                .chatMemoryStore(brokenStore)
                .maxMessages(ChatHistoryConstant.MEMORY_MAX_MESSAGES)
                .build();

        // 不抛异常 = 生成请求还能继续（改造前这里是 service 里 catch 掉并返回 0）
        assertDoesNotThrow(() -> new TestFactory(service, brokenStore).load(1L, memory));
    }
}
