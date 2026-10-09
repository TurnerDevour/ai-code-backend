package com.example.aicodebackend.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 测试用的 {@link TokenStream} 桩
 * <p>
 * HTML / 多文件 / Vue 工程三个模式都通过 {@code TokenStream} 取模型输出（这样才能同时拿到
 * 正文与思考两条增量，见 {@code AiCodeGeneratorService}），因此单测里的假服务也要能产出 TokenStream。
 * {@link TokenStream} 的 {@code onPartialThinking} 等方法没有可用的默认实现（默认实现会抛
 * {@code UnsupportedOperationException}），所以必须显式实现，不能只实现 {@code onPartialResponse}。
 * <p>
 * 语义与真实 TokenStream 一致：先注册回调，{@code start()} 时才按顺序回调。
 */
public class StubTokenStream implements TokenStream {

    private final List<String> responseChunks;

    private final List<String> thinkingChunks;

    private Consumer<String> partialResponseConsumer = chunk -> {
    };

    private Consumer<PartialThinking> partialThinkingConsumer = partialThinking -> {
    };

    private Consumer<ToolExecution> toolExecutedConsumer = toolExecution -> {
    };

    private Consumer<ChatResponse> completeResponseConsumer = chatResponse -> {
    };

    private Consumer<Throwable> errorConsumer = throwable -> {
    };

    /**
     * 只产出正文增量的桩
     *
     * @param responseChunks 正文增量（按给定顺序回调）
     */
    public StubTokenStream(List<String> responseChunks) {
        this(responseChunks, List.of());
    }

    /**
     * 同时产出思考增量的桩
     *
     * @param responseChunks 正文增量
     * @param thinkingChunks 思考增量（在正文之前回调，与真实模型一致）
     */
    public StubTokenStream(List<String> responseChunks, List<String> thinkingChunks) {
        this.responseChunks = new ArrayList<>(responseChunks);
        this.thinkingChunks = new ArrayList<>(thinkingChunks);
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> consumer) {
        this.partialResponseConsumer = consumer;
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> consumer) {
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
        thinkingChunks.forEach(chunk -> partialThinkingConsumer.accept(new PartialThinking(chunk)));
        responseChunks.forEach(partialResponseConsumer);
        completeResponseConsumer.accept(ChatResponse.builder()
                .aiMessage(AiMessage.from(String.join("", responseChunks)))
                .build());
    }
}
