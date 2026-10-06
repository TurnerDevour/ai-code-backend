package com.example.aicodebackend.core.generation;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.AppCodeStateService;
import com.example.aicodebackend.service.ChatHistoryService;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成任务注册表（方案 C 的原型，当前只接 VUE_PROJECT）
 * <p>
 * 要解决的问题：原来的生成流与"下发 SSE 的流"是同一条链，客户端断开（用户点返回/关页面）会取消整条链，
 * 于是"模型继续生成的内容"进不了对话历史——实测断开那刻历史里只有 122 字符，而磁盘上的产物有 963 字符。
 * <p>
 * 这里把一条生成拆成两个独立的订阅方：
 * <ul>
 *     <li><b>appId 订阅（累积 + 落库）</b>：内部订阅上游生成流，负责把消息解析成可展示文本累积起来，
 *     并在<b>生成真正结束</b>时写入对话历史。它不对外暴露、也不会被客户端影响，因此历史里拿到的是完整内容；</li>
 *     <li><b>subId 订阅（下发 SSE）</b>：每个 HTTP 请求（或浏览器重连）用不同的 subId 订阅同一份广播，
 *     客户端断开只取消自己的 subId 订阅，不影响 appId 订阅与上游生成。</li>
 * </ul>
 * 同一应用重复提交会复用正在运行的任务；已结束的任务可以被读取（内容与状态）。
 * <p>
 * 为什么用"手动投递"而不是 Reactor 的 Sinks：sink 的订阅是异步建立的，对同步源（单测里的 Flux.just）
 * 与"客户端尚未完成订阅"的时序都很敏感，实测会出现丢头部分片/重复投递；
 * 这里直接持有一个 subscriber 列表，subId 订阅在 {@code doOnSubscribe} 时同步登记，语义确定。
 */
@Slf4j
@Component
public class GenerationTaskRegistry {

    /**
     * 单条 SSE 数据（sseChunk 已在控制器层做过 JSON 序列化，保持"下发一次、记录一次"的一致性）
     */
    public record SseFrame(long seq, String sseChunk) {
    }

    /**
     * 带序号的输出帧：控制器把它转成 SSE 的 {@code id:}，客户端续订时回传该序号，
     * 从而精确补发"客户端没收到的那部分"。
     * <p>
     * 为什么需要它：服务端的"已产出序号"与"客户端实际收到的序号"会不一致
     * （生成器继续产出、HTTP 层还有缓冲），只用服务端序号续订会把客户端已经收到的帧再发一遍。
     */
    public record SequencedFrame(long seq, String data) {
    }

    /**
     * 生成任务
     */
    @Data
    public static class GenerationTask {
        /** 应用ID（一个应用同时只有一条生成任务） */
        private final Long appId;
        /** 触发本次生成的用户 */
        private final Long userId;
        /** 对话历史里已存在的 AI 消息条数（用于判断是否需要重放） */
        private final int historyAiCountBefore;
        /** 累积的展示内容（与落库内容一致） */
        private volatile StringBuilder content = new StringBuilder();
        /** 已下发的 SSE 帧（重连补发用） */
        private final List<SseFrame> frames = new ArrayList<>();
        /** 帧序号 */
        private long frameSeq = 0;
        /** 订阅者：subId -> 订阅者 */
        private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
        /** 已断开订阅者的最后下发序号：续订时用它判断"客户端到底收到了哪一帧" */
        private final Map<String, Long> lastSentSeqBySub = new ConcurrentHashMap<>();
        private volatile GenerationStatus status = GenerationStatus.RUNNING;
        private volatile String errorMessage = "";
        private volatile long startedAt = System.currentTimeMillis();
        private volatile long finishedAt = 0;
        /**
         * 生成结束后的构建状态（Vue 工程用）
         * <p>
         * 前端在收到 done 之后需要知道 dist 是否已经产出：生成结束到 dist 落地之间有
         * 一次 npm install + vite build（十几秒到一分钟），这段时间里刷新预览只会看到 404 或旧产物，
         * 所以这里把构建状态暴露给前端轮询，构建完成后再刷新预览。
         */
        private volatile BuildStatus buildStatus = BuildStatus.IDLE;
        /** 构建失败原因（buildStatus=FAILED 时） */
        private volatile String buildError = "";
        /** 构建开始/结束时间（便于排查构建耗时） */
        private volatile long buildStartedAt = 0;
        private volatile long buildFinishedAt = 0;
        /** 上游订阅句柄：任务结束时统一释放 */
        private volatile Disposable upstream;

        GenerationTask(Long appId, Long userId, int historyAiCountBefore) {
            this.appId = appId;
            this.userId = userId;
            this.historyAiCountBefore = historyAiCountBefore;
        }

        /** 是否可以被复用（同一个应用正在生成时，后来的请求直接复用） */
        public boolean isRunning() {
            return status == GenerationStatus.RUNNING;
        }

        /** 已累积内容的字符数（状态接口用，避免把整段内容传出去） */
        public int contentLength() {
            return content.length();
        }
    }

    /**
     * 生成状态
     */
    public enum GenerationStatus {
        /** 生成中 */
        RUNNING("running"),
        /** 生成完成（含完整内容已落库） */
        FINISHED("finished"),
        /** 生成失败 */
        FAILED("failed");

        private final String value;

        GenerationStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 生成结束后的构建状态（仅 Vue 工程会进入 RUNNING/FINISHED/FAILED，其它类型恒为 IDLE）
     */
    public enum BuildStatus {
        /** 尚未触发构建（非 Vue 工程，或还没走到收尾） */
        IDLE("idle"),
        /** 正在构建（npm install + vite build） */
        RUNNING("running"),
        /** 构建完成，dist 已就绪 */
        FINISHED("finished"),
        /** 构建失败（dist 可能是旧的或不存在） */
        FAILED("failed");

        private final String value;

        BuildStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * 上游生成流的产出：一条 SSE 数据帧 + 需要累积进历史的文本
     */
    public record GenerationEmit(String sseChunk, String historyChunk) {
    }

    private final Map<Long, GenerationTask> tasks = new ConcurrentHashMap<>();

    private final ChatHistoryService chatHistoryService;

    private final VueProjectBuilder vueProjectBuilder;

    /**
     * 代码状态服务：生成结束后刷新 edit_time，让"改完代码可重新部署"能被识别
     */
    private final AppCodeStateService appCodeStateService;

    public GenerationTaskRegistry(ChatHistoryService chatHistoryService,
                                  VueProjectBuilder vueProjectBuilder,
                                  AppCodeStateService appCodeStateService) {
        this.chatHistoryService = chatHistoryService;
        this.vueProjectBuilder = vueProjectBuilder;
        this.appCodeStateService = appCodeStateService;
    }

    /**
     * 提交（或复用）生成任务
     *
     * @param appId           应用ID
     * @param loginUser       登录用户
     * @param upstreamFactory 上游生成流工厂：只有真正需要跑新任务时才会被调用
     *
     * @return 任务（调用方随后用 {@link #subscribe} 消费）
     */
    public GenerationTask submitOrGet(Long appId, User loginUser,
                                      java.util.function.Supplier<Flux<GenerationEmit>> upstreamFactory) {
        GenerationTask running = tasks.get(appId);
        if (running != null && running.isRunning()) {
            log.info("应用已有进行中的生成任务，复用该任务：appId={}", appId);
            return running;
        }
        // 新一轮生成：记录当前 AI 消息条数，便于判断"这一轮是否已经落库"
        int aiCount = countAiMessages(appId);
        GenerationTask task = new GenerationTask(appId, loginUser.getId(), aiCount);
        tasks.put(appId, task);
        log.info("提交生成任务：appId={}, 历史 AI 消息数={}", appId, aiCount);
        startTask(task, upstreamFactory.get());
        return task;
    }

    /**
     * 用 subId 订阅任务的输出（每个 HTTP 请求一个 subId；客户端断开只影响自己）
     *
     * @param task    任务
     * @param subId   订阅标识（建议用请求 id）
     * @param fromSeq 从该序号之后开始补发（0 表示从头，即当前累积的全部内容）
     *
     * @return 下发给该订阅者的 SSE 流
     */
    public Flux<SequencedFrame> subscribe(GenerationTask task, String subId, long fromSeq) {
        return Flux.<SequencedFrame>create(sink -> {
            // 1) 先补发历史帧（重连时把用户离开期间的内容补齐）
            List<SseFrame> replay;
            synchronized (task) {
                replay = task.frames.stream().filter(frame -> frame.seq() > fromSeq).toList();
            }
            replay.forEach(frame -> sink.next(new SequencedFrame(frame.seq(), frame.sseChunk())));
            // 记录该订阅者"已经收到哪一帧"：这是续订补发的唯一依据。
            // 不能用"登记时刻的 task.frameSeq"：补发期间可能又有新帧写入，
            // 只记 frameSeq 会让后续重连把已经发过的帧再补一遍。
            long[] lastSentSeq = {replay.isEmpty() ? fromSeq : replay.get(replay.size() - 1).seq()};

            // 2) 任务已结束：补发完直接结束（失败原因由控制器按状态补一条错误帧）
            if (!task.isRunning()) {
                sink.complete();
                return;
            }

            // 3) 登记订阅者：后续帧由 {@link #emit} 实时投递，并逐帧推进"已下发序号"
            Subscriber subscriber = new Subscriber(subId, sink, lastSentSeq);
            task.subscribers.put(subId, subscriber);
            sink.onCancel(() -> {
                task.subscribers.remove(subId);
                task.lastSentSeqBySub.put(subId, subscriber.lastSentSeq()[0]);
                log.info("订阅者断开（生成继续）：appId={}, subId={}, 已下发到 seq={}", task.getAppId(), subId, subscriber.lastSentSeq()[0]);
            });
            sink.onDispose(() -> {
                task.subscribers.remove(subId);
                task.lastSentSeqBySub.put(subId, subscriber.lastSentSeq()[0]);
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 查询某个订阅者最后收到的帧序号（断开后用于续订；未知返回 0）
     *
     * @param task  任务
     * @param subId 订阅标识
     *
     * @return 已下发到的最新帧序号
     */
    public long lastSentSeq(GenerationTask task, String subId) {
        Subscriber subscriber = task.subscribers.get(subId);
        if (subscriber != null) {
            return subscriber.lastSentSeq()[0];
        }
        return task.lastSentSeqBySub.getOrDefault(subId, 0L);
    }

    /**
     * 订阅者：持有 sink 与"已下发到哪一帧"，便于断开时记录进度
     *
     * @param subId       订阅标识
     * @param sink        下发通道
     * @param lastSentSeq 已下发帧序号（单元素数组，便于 lambda 内推进）
     */
    private record Subscriber(String subId, FluxSink<SequencedFrame> sink, long[] lastSentSeq) {
    }

    /**
     * 等待某个应用的任务结束（测试/内部使用）
     *
     * @param appId     应用ID
     * @param timeoutMs 超时时间（毫秒）
     *
     * @return 是否在超时前结束
     */
    public boolean awaitFinished(Long appId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            GenerationTask task = tasks.get(appId);
            if (task == null || !task.isRunning()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 查询任务（可能为 null）
     */
    public Optional<GenerationTask> find(Long appId) {
        return Optional.ofNullable(tasks.get(appId));
    }

    /**
     * 启动任务：独立订阅上游，累积 + 落库 + 广播
     */
    private void startTask(GenerationTask task, Flux<GenerationEmit> upstream) {
        Disposable disposable = upstream.subscribe(
                emit -> {
                    if (emit == null) {
                        return;
                    }
                    if (StrUtil.isNotEmpty(emit.historyChunk())) {
                        task.getContent().append(emit.historyChunk());
                    }
                    if (StrUtil.isNotEmpty(emit.sseChunk())) {
                        emit(task, emit.sseChunk());
                    }
                },
                error -> finish(task, GenerationStatus.FAILED, "AI回复失败: " + (error == null ? "未知错误" : error.getMessage())),
                () -> finish(task, GenerationStatus.FINISHED, null)
        );
        task.setUpstream(disposable);
    }

    /**
     * 广播一帧：记录序号后投递给所有订阅者，并推进每个订阅者的"已下发序号"
     */
    private void emit(GenerationTask task, String sseChunk) {
        List<Subscriber> targets;
        long seq;
        synchronized (task) {
            seq = ++task.frameSeq;
            task.frames.add(new SseFrame(seq, sseChunk));
            targets = new ArrayList<>(task.subscribers.values());
        }
        long frameSeq = seq;
        targets.forEach(subscriber -> {
            try {
                subscriber.sink().next(new SequencedFrame(frameSeq, sseChunk));
                subscriber.lastSentSeq()[0] = frameSeq;
            } catch (Exception e) {
                // 订阅者已经断开等情况：忽略，生成继续
                log.debug("下发 SSE 帧失败（订阅者可能已断开）：appId={}, error={}", task.getAppId(), e.getMessage());
            }
        });
    }

    /**
     * 生成真正结束时的收尾：落库完整内容 + 结束所有订阅者 + 触发构建（只执行一次）
     *
     * @param task       任务
     * @param status     结束状态
     * @param errorMsg   失败原因
     */
    private void finish(GenerationTask task, GenerationStatus status, String errorMsg) {
        synchronized (task) {
            if (!task.isRunning()) {
                return;
            }
            task.setStatus(status);
            task.setErrorMessage(StrUtil.nullToEmpty(errorMsg));
            task.setFinishedAt(System.currentTimeMillis());
        }
        String content = task.getContent().toString();
        // 1) 落库：此时是生成真正结束后的<b>完整</b>内容（客户端是否还在都不影响）
        try {
            if (status == GenerationStatus.FAILED) {
                chatHistoryService.addChatMessage(task.getAppId(), task.getUserId(), task.getErrorMessage(), ChatMessageTypeEnum.AI);
            } else if (StrUtil.isBlank(content)) {
                log.info("生成结束但没有任何内容，不写入对话历史：appId={}", task.getAppId());
            } else {
                chatHistoryService.addChatMessage(task.getAppId(), task.getUserId(), content, ChatMessageTypeEnum.AI);
            }
            log.info("生成任务结束：appId={}, status={}, 落库字符数={}", task.getAppId(), status, content.length());
        } catch (Exception e) {
            log.error("保存生成结果到对话历史失败：appId={}", task.getAppId(), e);
        }
        // 2) 结束所有订阅者（客户端断开与否都到这里收口）
        List<Subscriber> targets;
        synchronized (task) {
            targets = new ArrayList<>(task.subscribers.values());
            task.subscribers.clear();
        }
        targets.forEach(subscriber -> {
            try {
                subscriber.sink().complete();
            } catch (Exception ignored) {
                // 订阅者已断开
            }
        });
        // 3) Vue 工程：生成结束后触发一次异步构建
        triggerBuild(task.getAppId());
        // 4) 代码变了：刷新 edit_time，让"已部署但代码有更新"能被识别（用户可重新部署）
        if (status == GenerationStatus.FINISHED) {
            appCodeStateService.markCodeChanged(task.getAppId());
        }
        // 5) 释放上游
        Disposable disposable = task.getUpstream();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    /**
     * 触发 Vue 工程异步构建（在独立线程上，避免阻塞收尾）
     * <p>
     * 构建状态写回任务对象：前端在生成结束后轮询 {@code /app/chat/gen/status}，
     * 等 buildStatus 变成 finished/failed 再刷新预览，避免刷新到"还没有 dist"的空白页。
     */
    private void triggerBuild(Long appId) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
        // 找不到任务（理论上不会发生）时也要构图，避免空指针
        GenerationTask task = tasks.get(appId);
        if (task != null) {
            task.setBuildStatus(BuildStatus.RUNNING);
            task.setBuildError("");
            task.setBuildStartedAt(System.currentTimeMillis());
            task.setBuildFinishedAt(0);
        }
        Schedulers.boundedElastic().schedule(() -> {
            boolean success = false;
            String error = "";
            try {
                success = vueProjectBuilder.buildProjectAsyncAndWait(projectPath);
                if (!success) {
                    error = "Vue 项目构建失败，请查看后端日志";
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? "Vue 项目构建异常" : e.getMessage();
                log.error("触发 Vue 项目构建失败：appId={}", appId, e);
            }
            if (task == null) {
                return;
            }
            task.setBuildStatus(success ? BuildStatus.FINISHED : BuildStatus.FAILED);
            task.setBuildError(success ? "" : error);
            task.setBuildFinishedAt(System.currentTimeMillis());
            log.info("Vue 项目构建结束：appId={}, 成功={}, 耗时(ms)={}",
                    appId, success, task.getBuildFinishedAt() - task.getBuildStartedAt());
        });
    }

    /**
     * 统计该应用已有的 AI 消息条数
     */
    private int countAiMessages(Long appId) {
        try {
            return chatHistoryService.countAiMessages(appId);
        } catch (Exception e) {
            log.warn("统计历史 AI 消息失败（按 0 处理）：appId={}", appId, e);
            return 0;
        }
    }

    /**
     * 中断标记（保留给需要区分"中断"语义的调用方）
     */
    public static String interruptedContent(String content) {
        return ChatHistoryConstant.INTERRUPTED_MARKER + StrUtil.nullToEmpty(content);
    }

    /**
     * 应用关闭：中断所有生成任务，避免线程悬挂
     */
    @PreDestroy
    public void shutdown() {
        log.info("关闭生成任务注册表，进行中的任务数: {}", tasks.values().stream().filter(GenerationTask::isRunning).count());
        tasks.values().forEach(task -> {
            Disposable disposable = task.getUpstream();
            if (disposable != null) {
                disposable.dispose();
            }
            task.subscribers.values().forEach(subscriber -> subscriber.sink().complete());
            task.subscribers.clear();
        });
    }

    /**
     * 是否处于"生成中"（对外的简洁判断）
     */
    public boolean isRunning(Long appId) {
        GenerationTask task = tasks.get(appId);
        return task != null && task.isRunning();
    }
}
