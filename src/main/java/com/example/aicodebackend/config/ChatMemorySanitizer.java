package com.example.aicodebackend.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对话记忆的工具消息清洗
 * 解决的问题（实测复现）：模型在流式输出里先发出工具调用（assistant 消息带 {@code tool_calls}），
 * 随后流发生异常/被中断（例如 {@code AI回复失败: null}），这次调用的结果就永远没有机会写进记忆……
 */
@Slf4j
public final class ChatMemorySanitizer {

    private ChatMemorySanitizer() {
    }

    /** 判断消息是否含有工具调用请求 */
    private static boolean hasToolRequests(ChatMessage message) {
        return message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests();
    }

    /** 清洗工具消息配对：删除"带 tool_calls 但没有完整结果"的 assistant 消息，以及孤立的工具结果 */
    public static List<ChatMessage> sanitizeToolMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> result = new ArrayList<>(messages.size());
        int removedRequests = 0;
        int removedResults = 0;

        // 已成功配对（assistant 与结果都在）的工具调用 id
        Set<String> pairedRequestIds = new HashSet<>();

        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (hasToolRequests(message)) {
                AiMessage aiMessage = (AiMessage) message;
                // 收集紧随其后的工具结果（langchain4j 保证结果是连续排列的）
                List<ToolExecutionResultMessage> results = new ArrayList<>();
                int next = index + 1;
                while (next < messages.size() && messages.get(next) instanceof ToolExecutionResultMessage result1) {
                    results.add(result1);
                    next++;
                }
                boolean complete = isComplete(aiMessage, results);
                if (!complete) {
                    removedRequests++;
                    removedResults += results.size();
                    log.warn("对话记忆里发现未配对的工具调用，已丢弃该轮工具上下文：请求数={}, 结果数={}",
                            aiMessage.toolExecutionRequests().size(), results.size());
                    index = next - 1;
                    continue;
                }
                aiMessage.toolExecutionRequests().forEach(request -> pairedRequestIds.add(request.id()));
                result.add(message);
                result.addAll(results);
                index = next - 1;
                continue;
            }
            if (message instanceof ToolExecutionResultMessage toolResult) {
                if (!pairedRequestIds.contains(toolResult.id())) {
                    // 结果没有对应的 assistant 请求（历史数据损坏）：同样丢弃，避免被接口拒绝
                    removedResults++;
                    log.warn("对话记忆里发现孤立的工具执行结果，已丢弃：toolCallId={}", toolResult.id());
                    continue;
                }
            }
            result.add(message);
        }

        if (removedRequests > 0 || removedResults > 0) {
            log.warn("对话记忆清洗完成：消息数 {} -> {}，丢弃工具调用 {} 条、工具结果 {} 条",
                    messages.size(), result.size(), removedRequests, removedResults);
        }
        return result;
    }

    /** 校验 assistant 的工具调用是否都有对应结果（id 一一匹配） */
    private static boolean isComplete(AiMessage aiMessage, List<ToolExecutionResultMessage> results) {
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        if (results.size() != requests.size()) {
            return false;
        }
        Set<String> resultIds = new HashSet<>();
        results.forEach(result -> resultIds.add(result.id()));
        for (ToolExecutionRequest request : requests) {
            if (!resultIds.contains(request.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在"新一轮对话开始之前"修复存储里的不完整工具上下文
     * 为什么只能在轮次边界调用：LangChain4j 的工具循环是"先把 assistant(tool_calls) 写进记忆，
     * 执行完工具再把结果写进去"，中间那一瞬间读到的记忆天然就是"有请求、没有结果"……
     */
    public static int repairStoredMessages(ChatMemoryStore store, Object memoryId) {
        if (store == null || memoryId == null) {
            return 0;
        }
        List<ChatMessage> stored = store.getMessages(memoryId);
        if (stored == null || stored.isEmpty()) {
            return 0;
        }
        List<ChatMessage> repaired = sanitizeToolMessages(stored);
        int removed = stored.size() - repaired.size();
        if (removed > 0) {
            // 必须写回：否则脏数据一直躺在存储里，每次新建记忆都要重新清一遍
            store.updateMessages(memoryId, repaired);
        }
        return removed;
    }

    /**
     * 按 {@code MessageWindowChatMemory} 的规则裁剪窗口
     * 规则与 langchain4j 保持一致：{@link SystemMessage} 固定在首位不被淘汰；
     * 淘汰 assistant 消息时，其后的工具结果一并淘汰。
     */
    public static List<ChatMessage> trimToWindow(List<ChatMessage> messages, int maxMessages) {
        if (messages == null || messages.size() <= maxMessages) {
            return messages;
        }
        List<ChatMessage> windowed = new ArrayList<>(messages);
        while (windowed.size() > maxMessages) {
            int evictIndex = windowed.get(0) instanceof SystemMessage ? 1 : 0;
            ChatMessage evicted = windowed.remove(evictIndex);
            if (hasToolRequests(evicted)) {
                while (windowed.size() > evictIndex && windowed.get(evictIndex) instanceof ToolExecutionResultMessage) {
                    windowed.remove(evictIndex);
                }
            }
        }
        return windowed;
    }
}
