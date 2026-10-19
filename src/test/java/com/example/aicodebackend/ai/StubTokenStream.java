package com.example.aicodebackend.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 测试用的 {@link TokenStream} 桩
 * HTML / 多文件 / Vue 工程三个模式都通过 {@code TokenStream} 取模型输出（这样才能同时拿到……
 */
public class StubTokenStream implements TokenStream {

    private final List<String> responseChunks;

    private final List<String> thinkingChunks;

    /** 是否已被调用方取消（生产代码在判定"思考跑飞"时会取消） */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private Consumer<String> partialResponseConsumer = chunk -> {
    };

    private BiConsumer<PartialThinking, PartialThinkingContext> partialThinkingConsumer = (partialThinking, context) -> {
    };

    private Consumer<ToolExecution> toolExecutedConsumer = toolExecution -> {
    };

    private Consumer<ChatResponse> completeResponseConsumer = chatResponse -> {
    };

    private Consumer<Throwable> errorConsumer = throwable -> {
    };

    /** 只产出正文增量的桩 */
    public StubTokenStream(List<String> responseChunks) {
        this(responseChunks, List.of());
    }

    /** 同时产出思考增量的桩 */
    public StubTokenStream(List<String> responseChunks, List<String> thinkingChunks) {
        this.responseChunks = new ArrayList<>(responseChunks);
        this.thinkingChunks = new ArrayList<>(thinkingChunks);
    }

    /** 模型调用是否已被取消（跑飞中止的断言点） */
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> consumer) {
        this.partialResponseConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> consumer) {
        this.partialThinkingConsumer = (partialThinking, context) -> consumer.accept(partialThinking);
        return this;
    }

    @Override
    public TokenStream onPartialThinkingWithContext(
            BiConsumer<PartialThinking, PartialThinkingContext> consumer) {
        this.partialThinkingConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
        this.toolExecutedConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
        this.completeResponseConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> consumer) {
        this.errorConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        return this;
    }

    @Override
    public void start() {
        StreamingHandle handle = new StreamingHandle() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        PartialThinkingContext context = new PartialThinkingContext(handle);
        for (String chunk : thinkingChunks) {
            if (cancelled.get()) {
                // 真实实现被取消后也会停止回调：这里保持同样的语义
                return;
            }
            partialThinkingConsumer.accept(new PartialThinking(chunk), context);
        }
        if (cancelled.get()) {
            return;
        }
        responseChunks.forEach(partialResponseConsumer);
        completeResponseConsumer.accept(ChatResponse.builder()
                .aiMessage(AiMessage.from(String.join("", responseChunks)))
                .build());
    }
}
