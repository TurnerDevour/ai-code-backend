package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import com.example.aicodebackend.utils.AppPathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 生成收尾处理器：统一"正常完成 / 出错 / 客户端断开"三种结束方式的落库语义
 * 背景（实测结论）：用户点返回/关闭页面时客户端会中止 SSE 连接，但服务端侧的模型调用与工具执行仍在继续：
 * 实测断开后 LLM 仍完成 2 轮请求、工具把 4 个文件写进磁盘。而原来的收尾只挂在 {@code doOnComplete} 上……
 */
@Slf4j
@Component
public class GenerationCompletionHandler {

    /** 客户端在开始阶段就断开、一个字都没收到时的占位内容 */
    private static final String EMPTY_INTERRUPTED_CONTENT = "（本轮生成在开始阶段就被中断，没有产生可展示的内容）";

    /**
     * 客户端断开时是否仍把已生成内容落库（默认开启）
     * 这是"服务端兜底"：前端方案 A 负责让正常返回场景不中断，这里兜住"用户真的关掉页面/断网/直接换设备"的情况。
     * 提供开关是为了能单独验证前端的兜底效果（关掉它，页面刷新后必须完全靠前端会话恢复内容）。
     */
    private static final boolean SAVE_ON_DISCONNECT = !"false".equalsIgnoreCase(
            System.getProperty("generation.save-on-disconnect", "true"));

    private final VueProjectBuilder vueProjectBuilder;

    public GenerationCompletionHandler() {
        this(new VueProjectBuilder());
    }

    public GenerationCompletionHandler(VueProjectBuilder vueProjectBuilder) {
        this.vueProjectBuilder = vueProjectBuilder;
    }

    /**
     * 处理一条生成流：透传下发 + 内联累积，并在三种结束方式下落库
     * 不累积思考过程（思考由调用方自己落库，例如 Vue 工程模式的生成任务注册表）。
     */
    public Flux<String> handle(Flux<String> originFlux,
                               Function<String, String> display,
                               BiFunction<String, StringBuilder, String> accumulator,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser,
                               boolean buildProject) {
        return handle(originFlux, display, accumulator, null, chatHistoryService, appId, loginUser, buildProject);
    }

    /**
     * 处理一条生成流：透传下发 + 内联累积（正文与思考分开），并在三种结束方式下落库
     * 为 null 表示该链路的思考过程由别处落库（Vue 工程模式走生成任务注册表）
     */
    public Flux<String> handle(Flux<String> originFlux,
                               Function<String, String> display,
                               BiFunction<String, StringBuilder, String> accumulator,
                               Function<String, String> thinkingAccumulator,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser,
                               boolean buildProject) {
        StringBuilder accumulated = new StringBuilder();
        // 思考过程单独累积：它要单独落库到 chat_history.thinking 并单独展示，绝不能并进正文，
        // 否则对话历史与多轮上下文里会夹着大段推理内容
        StringBuilder thinking = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);
        // 同时产出的展示内容：display 可能因为去重而不产出（例如工具请求已经被累积侧消费过），
        // 此时把累积侧的内容作为下发内容，保证"下发给客户端的内容"与"落库内容"完全一致、不会丢消息
        java.util.concurrent.atomic.AtomicReference<String> emitted = new java.util.concurrent.atomic.AtomicReference<>("");
        return originFlux
                .doOnNext(chunk -> {
                    // 累积（落库用）：与客户端是否在线无关，写的是本地 StringBuilder
                    emitted.set("");
                    try {
                        String toAccumulate = accumulator == null ? chunk : accumulator.apply(chunk, accumulated);
                        if (StrUtil.isNotEmpty(toAccumulate)) {
                            accumulated.append(toAccumulate);
                            emitted.set(toAccumulate);
                        }
                    } catch (Exception e) {
                        log.error("累积生成内容失败，appId: {}", appId, e);
                    }
                    if (thinkingAccumulator != null) {
                        try {
                            String toAccumulate = thinkingAccumulator.apply(chunk);
                            if (StrUtil.isNotEmpty(toAccumulate)) {
                                thinking.append(toAccumulate);
                            }
                        } catch (Exception e) {
                            log.error("累积思考过程失败，appId: {}", appId, e);
                        }
                    }
                })
                .map(chunk -> {
                    String displayed = display == null ? chunk : StrUtil.nullToEmpty(display.apply(chunk));
                    if (StrUtil.isNotEmpty(displayed)) {
                        return displayed;
                    }
                    // display 没产出（去重命中）：回落到本次累积内容，避免客户端少收到一条工具消息
                    return emitted.get();
                })
                .filter(StrUtil::isNotEmpty)
                .doOnComplete(() -> finish(chatHistoryService, appId, loginUser, accumulated, thinking, persisted, buildProject,
                        FinishType.COMPLETED, null))
                .doOnError(error -> finish(chatHistoryService, appId, loginUser, accumulated, thinking, persisted, buildProject,
                        FinishType.FAILED, "AI回复失败: " + (error == null ? "未知错误" : error.getMessage())))
                .doOnCancel(() -> finish(chatHistoryService, appId, loginUser, accumulated, thinking, persisted, buildProject,
                        FinishType.INTERRUPTED, null));
    }

    /** 结束类型 */
    private enum FinishType {
        /** 正常完成 */
        COMPLETED,
        /** 出错 */
        FAILED,
        /** 客户端断开（生成可能仍在后台继续，但这条链已经终止） */
        INTERRUPTED
    }

    /** 生成结束时的收尾：落库 +（可选）触发构建，且只执行一次 */
    private void finish(ChatHistoryService chatHistoryService,
                        long appId,
                        User loginUser,
                        StringBuilder accumulated,
                        StringBuilder thinking,
                        AtomicBoolean persisted,
                        boolean buildProject,
                        FinishType finishType,
                        String errorMessage) {
        if (!persisted.compareAndSet(false, true)) {
            return;
        }
        String content = accumulated.toString();
        String toSave;
        switch (finishType) {
            case FAILED -> toSave = StrUtil.isBlank(errorMessage) ? "AI回复失败" : errorMessage;
            case INTERRUPTED -> {
                if (!SAVE_ON_DISCONNECT) {
                    log.info("客户端断开，按配置跳过落库（generation.save-on-disconnect=false），appId: {}", appId);
                    toSave = null;
                    break;
                }
                // 客户端断开：把已生成的部分保存下来并加明确标记，
                // 保证用户回到页面能看到"这一轮被中断了"，上下文也不会整段丢失
                toSave = ChatHistoryConstant.INTERRUPTED_MARKER
                        + (StrUtil.isBlank(content) ? EMPTY_INTERRUPTED_CONTENT : content);
                log.info("客户端断开，生成收尾：appId: {}, 已累积字符数: {}", appId, content.length());
            }
            default -> {
                if (StrUtil.isBlank(content)) {
                    // 正常完成但没有任何文本：模型只调用了工具，没有可落库的文本内容，记录占位便于排查
                    log.info("生成正常完成但没有可落库内容，appId: {}", appId);
                    toSave = null;
                } else {
                    toSave = content;
                }
            }
        }
        if (toSave != null) {
            String thinkingText = thinking == null ? "" : thinking.toString();
            try {
                if (StrUtil.isNotEmpty(thinkingText)) {
                    // 思考过程单独一列：不进 message，避免历史正文与多轮上下文被推理内容淹没
                    chatHistoryService.addAiChatMessage(appId, loginUser.getId(), toSave, thinkingText);
                    log.info("生成结束已写入对话历史，appId: {}, 类型: {}, 字符数: {}, 思考字符数: {}",
                            appId, finishType, toSave.length(), thinkingText.length());
                } else {
                    chatHistoryService.addChatMessage(appId, loginUser.getId(), toSave, ChatMessageTypeEnum.AI);
                    log.info("生成结束已写入对话历史，appId: {}, 类型: {}, 字符数: {}", appId, finishType, toSave.length());
                }
            } catch (Exception e) {
                log.error("保存 AI 回复到对话历史失败，appId: {}", appId, e);
            }
        }
        if (buildProject) {
            String projectPath = AppPathUtils.vueProjectDir(appId).toString();
            try {
                vueProjectBuilder.buildProjectAsync(projectPath);
            } catch (Exception e) {
                log.error("触发 Vue 项目构建失败，appId: {}", appId, e);
            }
        }
    }
}
