package com.example.aicodebackend.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对话记忆的工具消息清洗
 * <p>
 * 解决的问题（实测复现）：模型在流式输出里先发出工具调用（assistant 消息带 {@code tool_calls}），
 * 随后流发生异常/被中断（例如 {@code AI回复失败: null}），这次调用的结果就永远没有机会写进记忆。
 * 于是 Redis 里留下了一条"有 tool_calls、没有对应 tool 结果"的 assistant 消息：
 * <pre>
 * An assistant message with 'tool_calls' must be followed by tool messages responding to each
 * 'tool_call_id'. (insufficient tool messages following tool_calls message)
 * </pre>
 * 更糟的是它会<b>持续生效</b>——之后每次请求都会带上这条记忆，该应用的对话就永久失败
 * （实测 appId 465089248937918464 连续三轮都是这个错误）。
 * <p>
 * 处理方式：读取记忆时做一次一致性校验，把"没有完整工具结果"的 assistant 消息
 * （以及它残留的结果消息）去掉。丢失的只是那一次未完成的工具调用上下文，
 * 相比"整个应用再也不能对话"，这是明显更好的取舍。
 * <p>
 * 同时按 {@code MessageWindowChatMemory} 的规则做窗口裁剪，保证写回的结果不会超过窗口上限
 * （否则下次加载还要再裁一次，且会与我们自己的 evict 语义不一致）。
 */
@Slf4j
public final class ChatMemorySanitizer {

    private ChatMemorySanitizer() {
    }

    /**
     * 判断消息是否含有工具调用请求
     *
     * @param message 对话消息
     *
     * @return true 表示这是一条带 tool_calls 的 assistant 消息
     */
    private static boolean hasToolRequests(ChatMessage message) {
        return message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests();
    }

    /**
     * 清洗工具消息配对：删除"带 tool_calls 但没有完整结果"的 assistant 消息，以及孤立的工具结果
     *
     * @param messages 原始消息列表
     *
     * @return 清洗后的新列表（无需清洗时返回等价的列表副本）
     */
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

    /**
     * 校验 assistant 的工具调用是否都有对应结果（id 一一匹配）
     *
     * @param aiMessage 带 tool_calls 的 assistant 消息
     * @param results   紧随其后的工具结果
     *
     * @return true 表示配对完整
     */
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
     * 按 {@code MessageWindowChatMemory} 的规则裁剪窗口
     * <p>
     * 规则与 langchain4j 保持一致：{@link SystemMessage} 固定在首位不被淘汰；
     * 淘汰 assistant 消息时，其后的工具结果一并淘汰。
     *
     * @param messages    消息列表
     * @param maxMessages 窗口上限
     *
     * @return 裁剪后的列表（未超限时原样返回）
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
