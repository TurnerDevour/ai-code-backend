package com.example.aicodebackend.core.generation;

import com.example.aicodebackend.core.builder.VueProjectBuilder;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.AppCodeStateService;
import com.example.aicodebackend.service.ChatHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成任务注册表（方案 C）的回归测试
 * <p>
 * 核心语义：客户端断开只影响"下发"，不影响"生成与落库"——
 * 历史里必须是**生成真正结束后的完整内容**，而不是断开那一刻的片段。
 */
class GenerationTaskRegistryTest {

    // 每个测试用独立的 appId：注册表对同一个 appId 会复用正在运行的任务，共享 appId 会让测试互相干扰
    private static final java.util.concurrent.atomic.AtomicLong APP_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(9000L);

    /** 事件名：代码变更时间（edit_time）已落库 */
    private static final String MARK_CODE_CHANGED = "edit-time";
    /** 事件名：订阅者收到结束信号（客户端看到 done） */
    private static final String SUBSCRIBER_COMPLETED = "subscriber-complete";

    /**
     * 取一个测试专用的 appId
     */
    private static long nextAppId() {
        return APP_ID_SEQ.incrementAndGet();
    }

    @Test
    @DisplayName("客户端中途断开：生成继续，历史里落库完整内容")
    void clientDisconnectShouldNotTruncateHistory() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);
        CountDownLatch shouldEmit = new CountDownLatch(3);

        // 用异步源模拟真实的流式生成（10 个分片，每 30ms 一个）
        Flux<GenerationTaskRegistry.GenerationEmit> upstream = Flux.interval(Duration.ofMillis(30))
                .take(10)
                .map(i -> new GenerationTaskRegistry.GenerationEmit("{\"type\":\"ai_response\",\"data\":\"c" + i + "\"}", "c" + i));

        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), () -> upstream);
        // 客户端只消费前 3 个分片就断开
        List<String> received = new ArrayList<>();
        reactor.core.Disposable subscription = registry.subscribe(task, "sub-1", 0L).map(GenerationTaskRegistry.SequencedFrame::data).subscribe(chunk -> {
            received.add(chunk);
            shouldEmit.countDown();
            if (received.size() == 3) {
                // 模拟用户点返回：取消客户端订阅
                throw new IllegalStateException("client-abort");
            }
        });
        assertTrue(shouldEmit.await(5, TimeUnit.SECONDS), "未能收到前 3 个分片");
        subscription.dispose();

        // 等待生成真正结束（10 个分片约 300ms）
        assertTrue(registry.awaitFinished(appId, 10_000), "生成任务未在超时内结束");

        assertEquals(1, persisted.size(), "应落库一次");
        String saved = persisted.get(0);
        for (int i = 0; i < 10; i++) {
            assertTrue(saved.contains("c" + i), "历史里应包含断开之后才生成的分片 c" + i + "，实际: " + saved);
        }
        assertTrue(received.size() >= 3, "客户端至少应收到已消费的分片");
        assertTrue(received.size() < 10, "客户端应确实提前断开（只收到部分分片）");
    }

    @Test
    @DisplayName("重连续订：按 fromSeq 补发缺失帧，不重复下发已收到的内容")
    void resumeShouldReplayOnlyMissingFrames() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);
        CountDownLatch firstTwo = new CountDownLatch(2);

        Flux<GenerationTaskRegistry.GenerationEmit> upstream = Flux.interval(Duration.ofMillis(40))
                .take(6)
                .map(i -> new GenerationTaskRegistry.GenerationEmit("frame-" + i, "c" + i));

        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), () -> upstream);
        List<String> firstClient = new CopyOnWriteArrayList<>();
        reactor.core.Disposable first = registry.subscribe(task, "sub-1", 0L).map(GenerationTaskRegistry.SequencedFrame::data).subscribe(chunk -> {
            firstClient.add(chunk);
            firstTwo.countDown();
        });
        assertTrue(firstTwo.await(5, TimeUnit.SECONDS), "首个订阅者未收到前两帧");
        first.dispose();
        // 等生成结束
        assertTrue(registry.awaitFinished(appId, 10_000), "生成任务未结束");

        List<String> resumed = registry.subscribe(task, "sub-2", 2L).map(GenerationTaskRegistry.SequencedFrame::data).collectList().block(Duration.ofSeconds(5));
        assertTrue(resumed != null && !resumed.isEmpty(), "续订应补发剩余帧");
        assertFalse(resumed.contains("frame-0"), "不应重复下发已经收到的 frame-0");
        assertFalse(resumed.contains("frame-1"), "不应重复下发已经收到的 frame-1");
        assertTrue(resumed.contains("frame-5"), "应补发最新帧 frame-5，实际: " + resumed);
    }

    @Test
    @DisplayName("同一应用重复提交：复用正在运行的任务，不会并行跑两次生成")
    void submitShouldReuseRunningTask() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);
        CountDownLatch done = new CountDownLatch(3);

        Flux<GenerationTaskRegistry.GenerationEmit> upstream = Flux.interval(Duration.ofMillis(60))
                .take(3)
                .map(i -> new GenerationTaskRegistry.GenerationEmit("frame-" + i, "c" + i));

        GenerationTaskRegistry.GenerationTask first = registry.submitOrGet(appId, user(), () -> upstream);
        GenerationTaskRegistry.GenerationTask second = registry.submitOrGet(appId, user(), () -> {
            throw new AssertionError("不应为重复提交创建新的上游流");
        });
        assertTrue(first == second, "重复提交应复用同一个任务");
        registry.subscribe(first, "sub-1", 0L).map(GenerationTaskRegistry.SequencedFrame::data).subscribe(chunk -> done.countDown());
        assertTrue(done.await(5, TimeUnit.SECONDS), "未收到全部帧");
        assertTrue(registry.awaitFinished(appId, 10_000), "任务未结束");
        // 落库发生在"状态置为结束"之后，这里等到落库完成再断言（否则会有时序抖动）
        awaitPersisted(persisted, 5_000);
        assertEquals(1, persisted.size(),
                "只应落库一次；诊断：状态=" + first.getStatus() + ", 帧数=" + first.getFrames().size()
                        + ", 内容=" + first.getContent());
    }

    /**
     * 等待落库完成（落库在"状态置为结束"之后执行，避免断言时序抖动）
     *
     * @param persisted 落库内容列表
     * @param timeoutMs 超时时间
     */
    private void awaitPersisted(List<String> persisted, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (persisted.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("生成失败：错误信息落库，订阅者收到结束信号")
    void failureShouldPersistErrorMessage() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);

        Flux<GenerationTaskRegistry.GenerationEmit> upstream =
                Flux.concat(Flux.just(new GenerationTaskRegistry.GenerationEmit("frame-0", "c0")),
                        Flux.error(new IllegalStateException("模型超时")));

        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), () -> upstream);
        List<String> received = registry.subscribe(task, "sub-1", 0L).map(GenerationTaskRegistry.SequencedFrame::data).collectList().block(Duration.ofSeconds(5));

        assertTrue(received != null && received.contains("frame-0"), "失败前的帧应已下发");
        assertTrue(registry.awaitFinished(appId, 5_000), "任务未结束");
        awaitPersisted(persisted, 5_000);
        assertEquals(1, persisted.size(), "应落库一次错误信息");
        assertTrue(persisted.get(0).contains("模型超时"), "应落库错误原因，实际: " + persisted.get(0));
        assertEquals(GenerationTaskRegistry.GenerationStatus.FAILED, task.getStatus());
    }

    private GenerationTaskRegistry newRegistry(List<String> persisted) {
        return newRegistry(persisted, null);
    }

    /**
     * 生成成功时，必须"先把代码标记为已变更（edit_time 落库），再通知客户端结束"
     * <p>
     * 为什么：前端收到 done 之后会立刻重新查询部署状态来把按钮从「已部署」切成「重新部署」。
     * 如果这时 edit_time 还没落库，前端会再缓存一次 {@code deployStale=false}，
     * 用户就会遇到"用 AI 改完内容却点不动部署按钮"（实测问题：无法二次部署）。
     */
    @Test
    @DisplayName("生成成功：先落 edit_time，再通知订阅者结束")
    void codeChangeShouldBeMarkedBeforeSubscribersComplete() {
        long appId = nextAppId();
        List<String> events = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(new CopyOnWriteArrayList<>(), events);

        // 用可手动驱动的上游：确保订阅者已登记后再结束，从而能观察到两个动作的先后顺序
        reactor.core.publisher.Sinks.Many<GenerationTaskRegistry.GenerationEmit> upstream =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), upstream::asFlux);

        List<String> received = new CopyOnWriteArrayList<>();
        registry.subscribe(task, "sub-1", 0L)
                .doOnComplete(() -> events.add(SUBSCRIBER_COMPLETED))
                .map(GenerationTaskRegistry.SequencedFrame::data)
                .subscribe(received::add);

        upstream.tryEmitNext(new GenerationTaskRegistry.GenerationEmit("frame-0", "c0"));
        upstream.tryEmitComplete();

        assertEquals(List.of(MARK_CODE_CHANGED, SUBSCRIBER_COMPLETED), events,
                "必须先落 edit_time 再让客户端看到结束，否则前端会缓存过期的 deployStale=false，部署按钮点不动");
        assertTrue(received.contains("frame-0"), "分片应正常下发，实际: " + received);
        assertTrue(registry.awaitFinished(appId, 5_000), "任务应已结束");
    }

    /**
     * 模型用文本"假装"调用工具时，必须显式告警
     * <p>
     * 实测事故：模型学会了照抄历史里的 {@code [工具调用] xxx} 文本格式，整轮只打印这种文本、
     * 一次工具都没执行，文件一个都没写，而对话里看起来和真实调用一模一样。
     */
    @Test
    @DisplayName("文本形式的假工具调用：落库内容里必须给出明确警告")
    void fakeToolCallsWrittenAsTextShouldBeWarned() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);

        // 模型输出的正文里"手写"了两条工具调用，但没有任何真实的工具执行
        String fakeText = "现在开始写入：\n[工具调用] writeToFile {\"relativeFilePath\":\"src/utils/i18n.js\"}\n"
                + "[工具调用] writeToFile {\"relativeFilePath\":\"src/main.js\"}\n完成";
        Flux<GenerationTaskRegistry.GenerationEmit> upstream = Flux.just(
                new GenerationTaskRegistry.GenerationEmit(
                        "{\"type\":\"ai_response\",\"data\":\"...\"}", fakeText));

        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), () -> upstream);
        registry.subscribe(task, "sub-1", 0L).blockLast(Duration.ofSeconds(5));
        assertTrue(registry.awaitFinished(appId, 5_000), "任务应已结束");
        awaitPersisted(persisted, 5_000);

        assertEquals(1, persisted.size());
        assertTrue(persisted.get(0).contains("是模型以纯文本输出的"),
                "必须明确告知这些\"调用\"没有执行、文件没变，实际: " + StrUtil.maxLength(persisted.get(0), 300));
        assertTrue(persisted.get(0).contains("2 处"), "应报告假调用处数");
    }

    /** 真实工具执行（带换行前缀的记录）不应被误判成假调用 */
    @Test
    @DisplayName("真实工具执行不会被误报为假调用")
    void realToolExecutionsShouldNotBeWarned() throws Exception {
        long appId = nextAppId();
        List<String> persisted = new CopyOnWriteArrayList<>();
        GenerationTaskRegistry registry = newRegistry(persisted);

        Flux<GenerationTaskRegistry.GenerationEmit> upstream = Flux.just(
                new GenerationTaskRegistry.GenerationEmit(
                        "{\"type\":\"tool_executed\"}",
                        "\n[工具调用] writeToFile {\"relativeFilePath\":\"src/App.vue\"}\n"));

        GenerationTaskRegistry.GenerationTask task = registry.submitOrGet(appId, user(), () -> upstream);
        registry.subscribe(task, "sub-1", 0L).blockLast(Duration.ofSeconds(5));
        assertTrue(registry.awaitFinished(appId, 5_000));
        awaitPersisted(persisted, 5_000);

        assertEquals(1, persisted.size());
        assertFalse(persisted.get(0).contains("纯文本输出"),
                "真实执行不能报警告，实际: " + persisted.get(0));
    }

    /**
     * @param persisted 落库内容收集（addChatMessage 的第 3 个参数）
     * @param events    关键动作的发生顺序，为 null 时不记录
     */
    private GenerationTaskRegistry newRegistry(List<String> persisted, List<String> events) {        ChatHistoryService chatHistoryService = (ChatHistoryService) Proxy.newProxyInstance(
                ChatHistoryService.class.getClassLoader(),
                new Class<?>[]{ChatHistoryService.class},
                (proxy, method, args) -> {
                    if ("addChatMessage".equals(method.getName()) && args != null && args.length >= 3) {
                        persisted.add(String.valueOf(args[2]));
                        return 1L;
                    }
                    if ("countAiMessages".equals(method.getName())) {
                        return persisted.size();
                    }
                    return switch (method.getName()) {
                        case "toString" -> "StubChatHistoryService";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
        // VueProjectBuilder 是无状态组件：测试里直接 new，构建会被放到 boundedElastic 上异步执行。
        // AppCodeStateService 只负责刷新 edit_time，这里用桩 mapper，避免测试依赖数据库。
        return new GenerationTaskRegistry(chatHistoryService, new VueProjectBuilder(), new AppCodeStateService(stubAppMapper(events)));
    }

    /**
     * 桩 AppMapper：只让 update 返回 1（表示刷新 edit_time 成功），并按需记录"代码已变更"这一动作
     *
     * @param events 记录动作顺序的列表，可为 null
     */
    private AppMapper stubAppMapper(List<String> events) {
        return (AppMapper) Proxy.newProxyInstance(
                AppMapper.class.getClassLoader(),
                new Class<?>[]{AppMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "update" -> {
                        if (events != null) {
                            events.add(MARK_CODE_CHANGED);
                        }
                        yield 1;
                    }
                    case "toString" -> "StubAppMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private User user() {
        User user = new User();
        user.setId(100L);
        return user;
    }
}
