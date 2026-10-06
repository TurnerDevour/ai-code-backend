package com.example.aicodebackend.core.handler;

import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.ChatHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成收尾（正常完成 / 出错 / 客户端断开）的回归测试
 * <p>
 * 回归背景：客户端断开时 {@code doOnComplete} 不会触发，原来的实现什么都不落库，
 * 于是出现"磁盘上有代码、对话历史里没有 AI 回复"的不一致。现在断开也要落库（带中断标记）。
 */
class GenerationCompletionHandlerTest {

    @Test
    @DisplayName("正常完成：完整内容落库一次，且不带中断标记")
    void normalCompletionShouldPersistOnce() {
        List<String> persisted = new ArrayList<>();
        List<String> received = new GenerationCompletionHandler()
                .handle(Flux.just("a", "b", "c"), chunk -> chunk, null, stub(persisted), 1L, new User(), false)
                .collectList()
                .block();

        assertEquals(List.of("a", "b", "c"), received, "客户端应收到全部分片");
        assertEquals(1, persisted.size(), "只应落库一次");
        assertEquals("abc", persisted.get(0), "落库内容应完整且无标记");
    }

    @Test
    @DisplayName("客户端断开：把已收到内容按中断语义落库，并加中断标记")
    void clientCancelShouldPersistPartialContentWithMarker() throws Exception {
        List<String> persisted = new ArrayList<>();
        ChatHistoryService chatHistoryService = stub(persisted);
        CountDownLatch firstChunk = new CountDownLatch(1);
        // 上游持续产出的流；客户端只消费第一个分片后取消
        Flux<String> origin = Flux.interval(java.time.Duration.ofMillis(30))
                .map(i -> "chunk-" + i)
                .cast(String.class);

        GenerationCompletionHandler handler = new GenerationCompletionHandler();
        Flux<String> clientFlux = handler.handle(origin, chunk -> chunk, null, chatHistoryService, 1L, new User(), false);
        reactor.core.Disposable subscription = clientFlux.subscribe(chunk -> firstChunk.countDown());
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "未收到首个分片");
        subscription.dispose();
        // 等待取消信号沿链路传播并完成落库
        long deadline = System.currentTimeMillis() + 5000;
        while (persisted.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(1, persisted.size(), "断开后应落库一次");
        String saved = persisted.get(0);
        assertTrue(saved.startsWith(ChatHistoryConstant.INTERRUPTED_MARKER), "应带中断标记，实际: " + saved);
        assertTrue(saved.contains("chunk-0"), "应保留断开前已收到的内容，实际: " + saved);
    }

    @Test
    @DisplayName("出错：错误信息落库一次，不影响已完成的分片下发")
    void errorShouldPersistErrorMessageOnce() {
        List<String> persisted = new ArrayList<>();
        List<String> received = new GenerationCompletionHandler()
                .handle(Flux.concat(Flux.just("start"), Flux.error(new IllegalStateException("模型超时"))),
                        chunk -> chunk, null, stub(persisted), 1L, new User(), false)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block();

        assertEquals(List.of("start"), received, "出错前收到的分片应已下发");
        assertEquals(1, persisted.size(), "只应落库一次");
        assertTrue(persisted.get(0).contains("模型超时"), "应落库错误原因，实际: " + persisted.get(0));
    }

    @Test
    @DisplayName("正常完成但没有任何内容：不写入空消息")
    void emptyCompletionShouldNotPersistEmptyMessage() {
        List<String> persisted = new ArrayList<>();
        new GenerationCompletionHandler()
                .handle(Flux.empty(), chunk -> chunk, null, stub(persisted), 1L, new User(), false)
                .collectList()
                .block();

        assertTrue(persisted.isEmpty(), "空内容不应落库空消息");
    }

    @Test
    @DisplayName("display 去重导致不下发时，回落到累积内容，保证客户端不丢工具消息")
    void shouldFallbackToAccumulatedContentWhenDisplayIsEmpty() {
        AtomicInteger accumulateCalls = new AtomicInteger();
        List<String> received = new GenerationCompletionHandler()
                .handle(
                        Flux.just("x"),
                        chunk -> "",
                        (chunk, accumulated) -> {
                            accumulateCalls.incrementAndGet();
                            return "[tool] " + chunk;
                        },
                        stub(new ArrayList<>()),
                        1L,
                        new User(),
                        false
                )
                .collectList()
                .block();

        assertEquals(1, accumulateCalls.get(), "累积应被调用一次");
        assertEquals(List.of("[tool] x"), received, "display 为空时应把累积内容下发给客户端");
    }

    /**
     * 手写 ChatHistoryService 桩对象（当前环境 Mockito 不可用）
     *
     * @param persistedMessages 收集落库内容
     */
    private ChatHistoryService stub(List<String> persistedMessages) {
        return (ChatHistoryService) Proxy.newProxyInstance(
                ChatHistoryService.class.getClassLoader(),
                new Class<?>[]{ChatHistoryService.class},
                (proxy, method, args) -> {
                    if ("addChatMessage".equals(method.getName()) && args != null && args.length >= 3) {
                        persistedMessages.add(String.valueOf(args[2]));
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
