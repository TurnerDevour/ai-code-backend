package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 生成收尾处理器：统一"正常完成 / 出错 / 客户端断开"三种结束方式的落库语义
 * <p>
 * 背景（实测结论）：用户点返回/关闭页面时客户端会中止 SSE 连接，但服务端侧的模型调用与工具执行<b>仍在继续</b>：
 * 实测断开后 LLM 仍完成 2 轮请求、工具把 4 个文件写进磁盘。而原来的收尾只挂在 {@code doOnComplete} 上，
 * 流被取消时不会触发，于是出现"磁盘上有代码、对话历史里什么都没有"的不一致：
 * 用户回到页面看到空回复，AI 后续追问也"不记得"生成过什么。
 * <p>
 * 处理方式：
 * <ul>
 *     <li>累积仍然内联在这条链上（累积写的是 StringBuilder，不需要客户端在线）；</li>
 *     <li>{@code doOnComplete}：正常完成 → 完整内容落库；</li>
 *     <li>{@code doOnError}：失败 → 错误信息落库；</li>
 *     <li>{@code doOnCancel}：客户端断开 → 把"断开这一刻"已累积的内容按中断语义落库并加标记。
 *     注意断开后生成可能还在继续，后续内容无法再进入这条链（它已经终止），
 *     因此这里保存的是"客户端实际看到过的内容"，与用户预期一致；
 *     若一个字都没收到，则写入一条明确的"无内容"提示，避免历史出现空洞。</li>
 * </ul>
 * 三种结束方式互斥（Reactor 的终止信号只有一个），并用一次性标记兜底，保证只落库一次。
 */
@Slf4j
@Component
public class GenerationCompletionHandler {

    /**
     * 客户端在开始阶段就断开、一个字都没收到时的占位内容
     */
    private static final String EMPTY_INTERRUPTED_CONTENT = "（本轮生成在开始阶段就被中断，没有产生可展示的内容）";

    /**
     * 客户端断开时是否仍把已生成内容落库（默认开启）
     * <p>
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
     *
     * @param originFlux         上游生成流
     * @param display            把上游 chunk 转成"下发给客户端的内容"，返回 null/空表示不下发
     * @param accumulator        内部内容丰富器（例如按工具调用补充展示内容），返回需要累积的内容
     * @param chatHistoryService 对话历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param buildProject       收尾时是否触发 Vue 工程异步构建
     *
     * @return 下发给客户端的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               Function<String, String> display,
                               BiFunction<String, StringBuilder, String> accumulator,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser,
                               boolean buildProject) {
        StringBuilder accumulated = new StringBuilder();
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
                .doOnComplete(() -> finish(chatHistoryService, appId, loginUser, accumulated, persisted, buildProject,
                        FinishType.COMPLETED, null))
                .doOnError(error -> finish(chatHistoryService, appId, loginUser, accumulated, persisted, buildProject,
                        FinishType.FAILED, "AI回复失败: " + (error == null ? "未知错误" : error.getMessage())))
                .doOnCancel(() -> finish(chatHistoryService, appId, loginUser, accumulated, persisted, buildProject,
                        FinishType.INTERRUPTED, null));
    }

    /**
     * 结束类型
     */
    private enum FinishType {
        /** 正常完成 */
        COMPLETED,
        /** 出错 */
        FAILED,
        /** 客户端断开（生成可能仍在后台继续，但这条链已经终止） */
        INTERRUPTED
    }

    /**
     * 生成结束时的收尾：落库 +（可选）触发构建，且只执行一次
     *
     * @param chatHistoryService 对话历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param accumulated        已累积的生成内容
     * @param persisted          一次性标记（三种结束方式互斥）
     * @param buildProject       是否触发构建
     * @param finishType         结束类型
     * @param errorMessage       失败时的消息
     */
    private void finish(ChatHistoryService chatHistoryService,
                        long appId,
                        User loginUser,
                        StringBuilder accumulated,
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
            try {
                chatHistoryService.addChatMessage(appId, loginUser.getId(), toSave, ChatMessageTypeEnum.AI);
                log.info("生成结束已写入对话历史，appId: {}, 类型: {}, 字符数: {}", appId, finishType, toSave.length());
            } catch (Exception e) {
                log.error("保存 AI 回复到对话历史失败，appId: {}", appId, e);
            }
        }
        if (buildProject) {
            String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
            try {
                vueProjectBuilder.buildProjectAsync(projectPath);
            } catch (Exception e) {
                log.error("触发 Vue 项目构建失败，appId: {}", appId, e);
            }
        }
    }

    /**
     * 中断标记的包装（供需要区分"中断"语义的调用方使用）
     *
     * @param content 已生成的内容
     *
     * @return 带中断标记的内容
     */
    public static String markInterrupted(String content) {
        return ChatHistoryConstant.INTERRUPTED_MARKER + (StrUtil.isBlank(content) ? EMPTY_INTERRUPTED_CONTENT : content);
    }
}
