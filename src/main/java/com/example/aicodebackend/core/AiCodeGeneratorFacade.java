package com.example.aicodebackend.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.ai.model.message.AiResponseMessage;
import com.example.aicodebackend.ai.model.message.AiThinkingMessage;
import com.example.aicodebackend.ai.model.message.ToolExecutedMessage;
import com.example.aicodebackend.ai.model.message.ToolRequestMessage;
import com.example.aicodebackend.ai.provider.AiModelProperties;
import com.example.aicodebackend.ai.tools.ToolMessageRenderer;
import com.example.aicodebackend.config.AiCodeGeneratorServiceFactory;
import com.example.aicodebackend.core.generation.ThinkingGuardProperties;
import com.example.aicodebackend.core.generation.ThinkingRunawayGuard;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.GeneratedFileEnum;
import com.example.aicodebackend.parser.CodeDamageDetector;
import com.example.aicodebackend.parser.CodeParserExecutor;
import com.example.aicodebackend.parser.GeneratedCodeRepair;
import com.example.aicodebackend.saver.CodeFileSaverExecutor;
import com.example.aicodebackend.utils.ThrowableUtils;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * AiCodeGeneratorFacade 是一个外观类，用于简化与 AI 代码生成器的交互。
 * 该类提供了统一的接口，封装了复杂的代码生成逻辑，使客户端可以更方便地使用 AI 代码生成功能。
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    /** 采纳修复结果时允许的最小长度比例 */
    private static final double MIN_REPAIR_LENGTH_RATIO = 0.9;

    /** 没有显式指定模型时使用的默认模型 */
    private static final AIModelTypeEnum DEFAULT_MODEL_TYPE = AIModelTypeEnum.DEFAULT_MODEL;

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /** 模型配置：只用来读本轮所选模型的 {@code thinking-budget}，见 {@link #newThinkingGuard} */
    @Resource
    private AiModelProperties aiModelProperties;

    /** 跑飞守卫的阈值覆盖（可选，默认按模型预算推导），见 {@link ThinkingGuardProperties} */
    @Resource
    private ThinkingGuardProperties thinkingGuardProperties;

    /**
     * 工具展示文本生成器：把工具的展示文案随流式消息一起下发，
     * 前端只渲染文本、不猜工具参数结构（见 {@code ToolMessageRenderer}）
     */
    @Resource
    private ToolMessageRenderer toolMessageRenderer;

    /** 根据给定的提示和代码生成类型生成代码，并以流式方式返回生成的代码。 */
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId, AIModelTypeEnum aiModelTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum, aiModelTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                // 正文与思考都要下发：正文进代码解析与落盘，思考走 ai_thinking 消息单独展示
                StringBuilder codeBuilder = new StringBuilder();
                TokenStream tokenStream = aiCodeGeneratorService.generateHTMLCodeStream(prompt);
                yield processCodeStream(toMessageStream(tokenStream, codeBuilder, aiModelTypeEnum), codeBuilder,
                        CodeGenTypeEnum.HTML, appId, aiCodeGeneratorService, prompt);
            }
            case MULTI_FILE -> {
                StringBuilder codeBuilder = new StringBuilder();
                TokenStream tokenStream = aiCodeGeneratorService.generateMultipleFileCodeStream(prompt);
                yield processCodeStream(toMessageStream(tokenStream, codeBuilder, aiModelTypeEnum), codeBuilder,
                        CodeGenTypeEnum.MULTI_FILE, appId, aiCodeGeneratorService, prompt);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, prompt);
                yield processCodeTokenStream(tokenStream, appId, aiModelTypeEnum);
            }
        };
    }

    /**
     * 把 HTML / 多文件模式的 {@link TokenStream} 转换成前端可消费的 JSON 消息流
     * 与 Vue 工程模式的差别只有一个：这两个模式没有工具，因此不需要 tool_request / tool_executed 消息。
     * 换成 JSON 消息（而不是以前的纯文本增量）是"思考过程能显示出来"的前提……
     */
    private Flux<String> toMessageStream(TokenStream tokenStream, StringBuilder codeBuilder,
                                         AIModelTypeEnum aiModelTypeEnum) {
        return Flux.create(sink -> {
            // 判定器每个订阅各建一个：Flux.create 可以被重复订阅，共享同一个计数器会让
            // 第二次订阅一上来就带着上一次的累积值，把正常的一轮直接判成跑飞
            ThinkingRunawayGuard guard = newThinkingGuard(aiModelTypeEnum);
            AtomicBoolean aborted = new AtomicBoolean(false);
            registerThinkingHandler(tokenStream, guard, aborted, sink);
            tokenStream.onPartialResponse(partialResponse -> {
                if (aborted.get() || StrUtil.isEmpty(partialResponse)) {
                    // 空增量（模型首个 delta 常常是空字符串）不下发，避免无意义的消息帧
                    return;
                }
                // 有正文产出 = 有进展：重新开始"思考跑飞"计时
                guard.onProgress();
                codeBuilder.append(partialResponse);
                sink.next(responseMessage(partialResponse));
            }).onCompleteResponse(chatResponse -> {
                log.info("代码生成完成，结束原因: {}", chatResponse.finishReason());
                sink.complete();
            }).onError(throwable -> {
                log.error("处理代码流时发生错误", throwable);
                sink.error(toUserFacingError(throwable));
            }).start();
        });
    }

    /**
     * 注册思考增量回调：累积、判定跑飞，必要时尽力取消这次模型调用
     * 为什么要尝试取消：只中断我们这条流的话，模型调用还会继续跑到 token 上限，白烧 token。
     * 但能不能取消取决于模型实现——见 {@link #cancelQuietly}。拿不到句柄时（TokenStream 实现不支持）……
     */
    private void registerThinkingHandler(TokenStream tokenStream,
                                         ThinkingRunawayGuard guard,
                                         AtomicBoolean aborted,
                                         FluxSink<String> sink) {
        Consumer<PartialThinking> emitThinking = partialThinking -> {
            String thinking = partialThinking.text();
            if (aborted.get() || StrUtil.isBlank(thinking)) {
                return;
            }
            guard.onThinking(thinking);
            if (guard.isRunaway()) {
                aborted.set(true);
                log.warn("思考跑飞，已中止本轮生成：{}，说明：模型既没有输出正文也没有调用工具", guard.describe());
                sink.error(thinkingRunawayError());
                return;
            }
            sink.next(thinkingMessage(thinking));
        };
        AtomicBoolean cancelAttempted = new AtomicBoolean(false);
        try {
            tokenStream.onPartialThinkingWithContext((partialThinking, context) -> {
                emitThinking.accept(partialThinking);
                if (aborted.get() && cancelAttempted.compareAndSet(false, true)) {
                    cancelQuietly(context.streamingHandle());
                }
            });
        } catch (UnsupportedOperationException e) {
            log.warn("当前 TokenStream 不支持取消句柄，思考跑飞时只能中断下发：{}", e.getMessage());
            tokenStream.onPartialThinking(emitThinking);
        }
    }

    /**
     * 尽力取消这次模型调用（取消不了也不能影响"用户已经看到失败原因"这件事）
     * 实测：langchain4j 里能不能取消由模型实现决定。{@code OpenAiStreamingChatModel} 走的是传统
     * String 回调路径，拿到的是 {@code CancellationUnsupportedStreamingHandle}，调用 {@code cancel()}……
     */
    private void cancelQuietly(StreamingHandle handle) {
        try {
            handle.cancel();
            log.info("已取消本次模型调用（思考跑飞）");
        } catch (RuntimeException e) {
            log.warn("当前模型实现不支持取消流式调用，已降级为只中断下发（模型侧由 thinking-budget 兜住）：{}",
                    e.getMessage());
        }
    }

    /** 思考跑飞的失败原因（直接给用户看，必须说清"怎么办"） */
    private static BusinessException thinkingRunawayError() {
        return new BusinessException(ErrorCode.SYSTEM_ERROR,
                "模型在思考阶段长时间没有产出（既没有回复正文，也没有调用工具），本轮已中止。"
                        + "常见原因是需求超出了当前能力（例如要求联网获取真实图片/实时数据）。"
                        + "请换一种说法、拆成更小的步骤，或改用可实现的替代方案后重试。");
    }

    /** 正文增量消息（{@code type=ai_response}） */
    private static String responseMessage(String text) {
        return JSONUtil.toJsonStr(new AiResponseMessage(text));
    }

    /**
     * 思考过程消息（{@code type=ai_thinking}）
     * 思考过程单独一种消息类型：前端放进对话页顶部的「AI 思考过程」面板，
     * 后端单独累积进 {@code chat_history.thinking}（不进正文，否则历史与上下文会被推理内容淹没）。
     */
    private static String thinkingMessage(String text) {
        return JSONUtil.toJsonStr(new AiThinkingMessage(text));
    }

    /** 流式生成代码（使用默认模型） */
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId, DEFAULT_MODEL_TYPE);
    }

    /**
     * 处理 TokenStream（VUE_PROJECT 模式），把模型输出、思考过程与工具调用转换为前端可消费的 JSON 消息流。
     * VUE_PROJECT 使用推理模型，模型在调用文件写入工具之前只会输出思考内容（reasoning_content），
     * 不会产生正常的文本增量。因此必须监听 onPartialThinking，否则前端在整个代码生成阶段收不到任何内容……
     */
    private Flux<String> processCodeTokenStream(TokenStream tokenStream) {
        return processCodeTokenStream(tokenStream, null, null);
    }

    /**
     * 处理 TokenStream（VUE_PROJECT 模式），并在记忆损坏时自愈
     * 自愈场景（实测）：模型流式发出工具调用后连接异常中断，记忆里留下"有 tool_calls、没有工具结果"的
     * assistant 消息，之后每次请求都被接口拒绝……
     */
    private Flux<String> processCodeTokenStream(TokenStream tokenStream, Long appId,
                                                AIModelTypeEnum aiModelTypeEnum) {
        return Flux.create(sink -> {
            // 判定器每个订阅各建一个，原因同 toMessageStream
            ThinkingRunawayGuard guard = newThinkingGuard(aiModelTypeEnum);
            AtomicBoolean aborted = new AtomicBoolean(false);
            tokenStream.onPartialResponse(partialResponse -> {
                if (aborted.get()) {
                    return;
                }
                // 有正文产出 = 有进展：重新开始"思考跑飞"计时
                guard.onProgress();
                log.info("VUE_PROJECT 文本增量: {}", StrUtil.maxLength(partialResponse, 200));
                sink.next(responseMessage(partialResponse));
            }).onToolExecuted(toolExecution -> {
                if (aborted.get()) {
                    return;
                }
                // 工具真的跑了 = 有进展：即使这一轮思考很长，也不能算跑飞
                guard.onProgress();
                ToolExecutionRequest request = toolExecution.request();
                // 展示文本由工具自己声明（见 ToolMessageRenderer）：前端不再按工具名猜参数结构，
                // 否则 modifyFile 也会被渲染成"写入文件 + 空代码块"
                ToolRequestMessage toolRequestMessage = new ToolRequestMessage(request);
                toolRequestMessage.setDisplay(toolMessageRenderer.renderRequest(request.name()));
                sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                toolExecutedMessage.setDisplay(toolMessageRenderer.renderExecuted(request.name(), request.arguments(),
                        toolExecution.hasFailed(), toolExecution.result()));
                sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
            }).onCompleteResponse(chatResponse -> {
                log.info("VUE_PROJECT 生成完成，结束原因: {}", chatResponse.finishReason());
                sink.complete();
            }).onError(throwable -> {
                log.error("处理代码流时发生错误", throwable);
                invalidateServiceCacheIfToolMessagesInvalid(throwable, appId);
                sink.error(toUserFacingError(throwable));
            });
            // 思考回调放在最后注册：它内部可能在判定跑飞时立刻结束这条流
            registerThinkingHandler(tokenStream, guard, aborted, sink);
            tokenStream.start();
        });
    }

    /**
     * 按本轮真正使用的模型创建「思考跑飞」判定器
     * 阈值必须跟着模型的 {@code thinking-budget} 走，而不是写死：预算决定"平台允许模型思考多久"，
     * 守卫是它之外的第二道防线。守卫比预算更严时，掐掉的是预算内的正常工作——线上误报……
     */
    private ThinkingRunawayGuard newThinkingGuard(AIModelTypeEnum aiModelTypeEnum) {
        Integer thinkingBudget = resolveThinkingBudget(aiModelTypeEnum);
        if (thinkingGuardProperties == null) {
            // 单测会直接 new 出本类（不经过 Spring），此时用"按预算推导"的默认阈值
            return new ThinkingRunawayGuard(ThinkingRunawayGuard.Limits.forThinkingBudget(thinkingBudget));
        }
        return new ThinkingRunawayGuard(thinkingGuardProperties.limitsFor(thinkingBudget));
    }

    /** 取本轮模型在平台侧的思考预算（{@code ai.model-defaults.thinking-budget} 或模型自己的覆盖值） */
    private Integer resolveThinkingBudget(AIModelTypeEnum aiModelTypeEnum) {
        if (aiModelProperties == null) {
            return null;
        }
        AIModelTypeEnum modelType = aiModelTypeEnum == null ? DEFAULT_MODEL_TYPE : aiModelTypeEnum;
        AiModelProperties.Model model = aiModelProperties.getModels().get(modelType.getValue());
        return model == null ? null : model.getThinkingBudget();
    }

    /**
     * 把框架层的兜底异常翻译成用户看得懂的失败原因
     * 目前只处理"工具轮次超限"：LangChain4j 超过 {@code maxToolCallingRoundTrips} 时抛出的
     * {@code Something is wrong, exceeded N tool calling round trips} 对用户没有意义……
     */
    private static Throwable toUserFacingError(Throwable throwable) {
        if (ThrowableUtils.anyMessageContains(throwable, "tool calling round trips")) {
            return new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "模型连续调用了过多轮工具（疑似在原地重复写同一批文件），已中止本轮生成。"
                            + "请重新发起；若仍复现，请把需求拆成更小的步骤。");
        }
        return throwable;
    }

    /**
     * 判断异常是否为"工具消息不完整"这类记忆损坏，是的话失效该应用的生成服务缓存
     * 只有清掉缓存的实例，下一次请求才会重新加载对话记忆——届时
     * {@link AiCodeGeneratorServiceFactory#getAiCodeGeneratorService} 会在新一轮开始处先做一次……
     */
    private void invalidateServiceCacheIfToolMessagesInvalid(Throwable throwable, Long appId) {
        if (appId == null || throwable == null) {
            return;
        }
        if (ThrowableUtils.anyMessageContains(throwable, "tool_calls", "tool messages")) {
            log.warn("检测到工具消息不完整的记忆损坏，失效生成服务缓存以便按清洗后的记忆重建，appId: {}", appId);
            aiCodeGeneratorServiceFactory.invalidateAiCodeGeneratorService(appId);
        }
    }

    /**
     * 处理消息流，将生成的代码保存到文件中。
     * 实测背景：HTML / 多文件模式下，模型并不总是遵守系统提示词——多文件模式经常只输出
     * {@code index.html} 一个代码块（有时连 CSS 一起漏掉），此时产物目录里没有 style.css / script.js……
     */
    private Flux<String> processCodeStream(Flux<String> messageStream,
                                          StringBuilder codeBuilder,
                                          CodeGenTypeEnum codeGenTypeEnum,
                                          Long appId,
                                          AiCodeGeneratorService aiCodeGeneratorService,
                                          String prompt) {
        return messageStream
                .doOnComplete(() -> {
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parserResult = CodeParserExecutor.executorParser(completeCode, codeGenTypeEnum);
                        parserResult = repairGeneratedFiles(parserResult, codeGenTypeEnum, aiCodeGeneratorService, prompt);
                        File saveDir = CodeFileSaverExecutor.executeSaver(parserResult, codeGenTypeEnum, appId);
                        log.info("{}代码已保存到文件: {}", codeGenTypeEnum, saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存代码到文件失败", e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存代码到文件失败");
                    }
                });
    }

    /**
     * 生成结果自检 + 一次自动修复（补齐缺失文件、修复丢失的空格）
     * 为什么必须有这一步（均为实测现象）：
     * 页面就是"裸 HTML"；</li>……
     */
    private Object repairGeneratedFiles(Object parserResult,
                                        CodeGenTypeEnum codeGenTypeEnum,
                                        AiCodeGeneratorService aiCodeGeneratorService,
                                        String prompt) {
        if (parserResult instanceof MultiFileCodeResult multiFileResult) {
            // 先做纯语法的标签修复：只有标签粘连（类名没被粘）时页面本来就可用，不必多花一次调用
            multiFileResult.setHtmlCode(GeneratedCodeRepair.normalizeHtml(multiFileResult.getHtmlCode()));
            return repairMultiFileResult(multiFileResult, aiCodeGeneratorService, prompt);
        }
        if (codeGenTypeEnum == CodeGenTypeEnum.HTML && parserResult instanceof HTMLCodeResult htmlCodeResult) {
            htmlCodeResult.setHtmlCode(GeneratedCodeRepair.normalizeHtml(htmlCodeResult.getHtmlCode()));
            return repairHtmlResult(htmlCodeResult, aiCodeGeneratorService, prompt);
        }
        return parserResult;
    }

    /**
     * 调用一次"修复"生成，并把流式增量拼成完整文本
     * 修复走的是流式方法（见 {@link AiCodeGeneratorService#repairGeneratedCodeStream}），
     * 这里把增量流聚合成完整文本后再交给代码块解析器。
     */
    private String callRepairModel(AiCodeGeneratorService aiCodeGeneratorService, String instruction) {
        List<String> chunks = aiCodeGeneratorService.repairGeneratedCodeStream(instruction).collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    /** 多文件模式自检与修复：缺失的补、丢空格的修 */
    private MultiFileCodeResult repairMultiFileResult(MultiFileCodeResult result,
                                                      AiCodeGeneratorService aiCodeGeneratorService,
                                                      String prompt) {
        String html = result.getHtmlCode();
        String css = result.getCssCode();
        String js = result.getJsCode();
        CodeDamageDetector.Damage damage = CodeDamageDetector.detect(html, css, js);

        Set<String> missing = new LinkedHashSet<>();
        if (StrUtil.isBlank(html)) {
            missing.add(GeneratedCodeRepair.FILE_HTML);
        }
        if (StrUtil.isBlank(css)) {
            missing.add(GeneratedCodeRepair.FILE_CSS);
        }
        if (StrUtil.isBlank(js)) {
            missing.add(GeneratedCodeRepair.FILE_JS);
        }

        Set<String> damaged = new LinkedHashSet<>();
        if (damage.html()) {
            damaged.add(GeneratedCodeRepair.FILE_HTML);
        }
        if (damage.css()) {
            damaged.add(GeneratedCodeRepair.FILE_CSS);
        }
        if (damage.js()) {
            damaged.add(GeneratedCodeRepair.FILE_JS);
        }
        // 本来就缺的文件只需要"补"，不需要"修"
        damaged.removeAll(missing);

        if (missing.isEmpty() && damaged.isEmpty()) {
            return result;
        }

        log.warn("多文件生成结果自检：缺失文件={}，丢空格受损文件={}（html 损伤分值 {} / css {} / js {}），尝试自动修复",
                missing, damaged, damage.htmlScore(), damage.cssScore(), damage.jsScore());

        String reply;
        try {
            reply = callRepairModel(aiCodeGeneratorService,
                    buildMultiFileRepairInstruction(prompt, result, missing, damaged));
        } catch (Exception e) {
            log.warn("自动修复调用失败（按已有内容落盘，用户可再追问）：{}", e.getMessage());
            return result;
        }
        Map<String, String> repaired = GeneratedCodeRepair.extractFiles(reply);
        if (repaired.isEmpty()) {
            log.warn("自动修复没有解析出任何文件（回复长度 {}），按已有内容落盘", StrUtil.length(reply));
            return result;
        }

        Set<String> wanted = new LinkedHashSet<>(missing);
        wanted.addAll(damaged);
        // 先落 CSS / JavaScript，再用"修复后的 CSS"当字典判断 HTML 里是否还有类名粘连
        if (wanted.contains(GeneratedCodeRepair.FILE_CSS)) {
            result.setCssCode(accept(css, repaired.get(GeneratedCodeRepair.FILE_CSS),
                    damage.cssScore(), CodeDamageDetector::scoreCss));
        }
        if (wanted.contains(GeneratedCodeRepair.FILE_JS)) {
            result.setJsCode(accept(js, repaired.get(GeneratedCodeRepair.FILE_JS),
                    damage.jsScore(), CodeDamageDetector::scoreJs));
        }
        if (wanted.contains(GeneratedCodeRepair.FILE_HTML)) {
            String cssDictionary = result.getCssCode();
            result.setHtmlCode(GeneratedCodeRepair.normalizeHtml(accept(html, htmlCandidate(repaired), damage.htmlScore(),
                    candidate -> CodeDamageDetector.scoreHtml(candidate, cssDictionary))));
        }
        result.setHtmlCode(GeneratedCodeRepair.normalizeHtml(result.getHtmlCode()));

        CodeDamageDetector.Damage after = CodeDamageDetector.detect(
                result.getHtmlCode(), result.getCssCode(), result.getJsCode());
        if (after.any()) {
            log.warn("自动修复后仍检测到空格丢失（html={}, css={}, js={}），已按修复结果落盘；如页面仍异常，可让用户再追问一次",
                    after.html(), after.css(), after.js());
        } else {
            log.info("自动修复完成：缺失={}，受损={}，落盘内容已通过自检", missing, damaged);
        }
        return result;
    }

    /** HTML 模式自检与修复（CSS / JavaScript 内联在 {@code <style>} / {@code <script>} 里，一并交给模型补空格） */
    private HTMLCodeResult repairHtmlResult(HTMLCodeResult result,
                                            AiCodeGeneratorService aiCodeGeneratorService,
                                            String prompt) {
        String html = result.getHtmlCode();
        int score = CodeDamageDetector.scoreHtml(html, CodeDamageDetector.extractInlineStyles(html));
        boolean missing = StrUtil.isBlank(html);
        if (!missing && score == 0) {
            return result;
        }
        log.warn("HTML 生成结果自检：{}（损伤分值 {}），尝试自动修复", missing ? "没有解析出 HTML" : "检测到空格丢失", score);

        StringBuilder instruction = new StringBuilder();
        instruction.append("你上一次的输出有问题，请修复后重新输出 index.html。\n\n原始需求：\n")
                .append(StrUtil.nullToEmpty(prompt))
                .append("\n\n问题：\nindex.html：标签名与属性之间、属性与属性之间丢了空格")
                .append("（例如 <divclass=\"box\"id=\"a\">、<metacharset=\"UTF-8\">），")
                .append("class 取值里的多个类名也被粘成了一个（例如 class=\"containerhero-inner\"），")
                .append("内联在 <style> / <script> 里的 CSS / JavaScript 同样可能丢了空格，请一并补回。\n\n");
        appendFileSection(instruction, GeneratedFileEnum.HTML.getFileName(), html,
                missing ? "需要新建" : "需要修复");
        instruction.append("请只输出 index.html 一个 Markdown 代码块；除补回丢失的空格外，不要改动任何内容。");

        try {
            Map<String, String> repaired = GeneratedCodeRepair.extractFiles(
                    callRepairModel(aiCodeGeneratorService, instruction.toString()));
            result.setHtmlCode(GeneratedCodeRepair.normalizeHtml(accept(html, htmlCandidate(repaired), score,
                    candidate -> CodeDamageDetector.scoreHtml(
                            candidate, CodeDamageDetector.extractInlineStyles(candidate)))));
        } catch (Exception e) {
            log.warn("自动修复调用失败（按已有内容落盘，用户可再追问）：{}", e.getMessage());
            return result;
        }
        if (CodeDamageDetector.scoreHtml(result.getHtmlCode(),
                CodeDamageDetector.extractInlineStyles(result.getHtmlCode())) > 0) {
            log.warn("自动修复后 index.html 仍检测到空格丢失，已按修复结果落盘；如页面仍异常，可让用户再追问一次");
        }
        return result;
    }

    /**
     * 构造多文件模式的修复指令
     * 指令里会带上待修复文件的现有内容：让模型"补空格"而不是"重写一遍"，可以最大限度保住原有设计；
     * 补齐缺失文件时，index.html 会作为「参考」一起给出，保证类名 / ID 对得上。
     */
    private String buildMultiFileRepairInstruction(String prompt,
                                                   MultiFileCodeResult result,
                                                   Set<String> missing,
                                                   Set<String> damaged) {
        StringBuilder instruction = new StringBuilder();
        instruction.append("你上一次的输出有问题，请修复后重新输出下面这些文件。\n\n原始需求：\n")
                .append(StrUtil.nullToEmpty(prompt)).append("\n\n问题清单：\n");
        int order = 1;
        if (missing.contains(GeneratedCodeRepair.FILE_HTML)) {
            instruction.append(order++).append(") index.html：本次输出里没有这个文件，需要按原始需求补一份完整页面。\n");
        }
        if (damaged.contains(GeneratedCodeRepair.FILE_HTML)) {
            instruction.append(order++).append(") index.html：标签名与属性之间、属性与属性之间丢了空格")
                    .append("（例如 <divclass=\"box\"id=\"a\">），class 取值里的多个类名也被粘成了一个")
                    .append("（例如 class=\"containerhero-inner\"，应为 class=\"container hero-inner\"），需要补回。\n");
        }
        if (missing.contains(GeneratedCodeRepair.FILE_CSS)) {
            instruction.append(order++).append(") style.css：本次输出里没有这个文件，需要补一份完整样式，类名必须与 index.html 完全一致。\n");
        }
        if (damaged.contains(GeneratedCodeRepair.FILE_CSS)) {
            instruction.append(order++).append(") style.css：属性值与单位、值与值之间丢了空格")
                    .append("（例如 padding:024px、transform:translateY(-2px)scale(1)），需要补回。\n");
        }
        if (missing.contains(GeneratedCodeRepair.FILE_JS)) {
            instruction.append(order++).append(") script.js：本次输出里没有这个文件，需要补一份完整交互脚本，选择器必须与 index.html 完全一致。\n");
        }
        if (damaged.contains(GeneratedCodeRepair.FILE_JS)) {
            instruction.append(order++).append(") script.js：关键字与标识符、标识符与运算符之间丢了空格")
                    .append("（例如 'usestrict';、varheader=null;、functionsetYear(){），需要补回。\n");
        }
        instruction.append("\n下面是相关文件的内容。标注为「需要修复」的文件只补回丢失的空格，")
                .append("逻辑、结构、类名、ID、文案一律不要改；标注为「参考」的文件内容是正确的，只用于对齐类名与结构。\n\n");
        appendFileSection(instruction, GeneratedFileEnum.HTML.getFileName(), result.getHtmlCode(),
                sectionStatus(GeneratedCodeRepair.FILE_HTML, missing, damaged));
        appendFileSection(instruction, GeneratedFileEnum.CSS.getFileName(), result.getCssCode(),
                sectionStatus(GeneratedCodeRepair.FILE_CSS, missing, damaged));
        appendFileSection(instruction, GeneratedFileEnum.JS.getFileName(), result.getJsCode(),
                sectionStatus(GeneratedCodeRepair.FILE_JS, missing, damaged));
        instruction.append("\n请只输出上面「需要修复」或「需要新建」的文件，按系统要求的 Markdown 代码块格式。");
        return instruction.toString();
    }

    /** 文件在修复指令里的标注 */
    private static String sectionStatus(String file, Set<String> missing, Set<String> damaged) {
        if (missing.contains(file)) {
            return "需要新建";
        }
        return damaged.contains(file) ? "需要修复" : "参考";
    }

    /** 往修复指令里追加一个文件的内容 */
    private static void appendFileSection(StringBuilder instruction, String fileName, String content, String status) {
        instruction.append("【").append(fileName).append("｜").append(status).append("】\n");
        if (StrUtil.isBlank(content)) {
            instruction.append("（本次输出中没有这个文件的内容，请按原始需求与 index.html 的结构新写一份完整实现）\n\n");
            return;
        }
        instruction.append("```").append(languageOf(fileName)).append('\n')
                .append(content.strip()).append("\n```\n\n");
    }

    /** 文件名 -> Markdown 代码块语言标记 */
    private static String languageOf(String fileName) {
        GeneratedFileEnum file = GeneratedFileEnum.fromFileName(fileName);
        return file == null ? "text" : file.getLanguage();
    }

    /** 取修复回复里的 index.html：不像完整页面的一律丢弃（避免把半截内容当成网页落盘） */
    private static String htmlCandidate(Map<String, String> repaired) {
        String candidate = repaired.get(GeneratedCodeRepair.FILE_HTML);
        if (candidate == null) {
            return null;
        }
        if (!GeneratedCodeRepair.looksLikeHtmlDocument(candidate)) {
            log.warn("自动修复返回的 index.html 不像完整页面，已丢弃该候选");
            return null;
        }
        return candidate;
    }

    /**
     * 决定是否采纳修复结果
     * 采纳条件：原值缺失时任何非空候选都可以；否则要求受损分值严格下降，
     * 并且长度不能明显缩水（防止模型用一小段片段"修复"整份文件）。
     */
    private static String accept(String original, String candidate, int originalScore, ToIntFunction<String> scorer) {
        if (StrUtil.isBlank(candidate)) {
            return original;
        }
        String trimmed = candidate.strip();
        if (StrUtil.isBlank(original)) {
            return trimmed;
        }
        if (trimmed.length() < original.strip().length() * MIN_REPAIR_LENGTH_RATIO) {
            log.warn("自动修复候选明显比原内容短（{} -> {}），已丢弃", original.strip().length(), trimmed.length());
            return original;
        }
        return scorer.applyAsInt(trimmed) < originalScore ? trimmed : original;
    }

    /** HTML 兜底修复：采纳/保留的内容里若仍有粘连标签，用纯语法规则再补一层 */
    private static String normalizeHtml(String html) {
        return GeneratedCodeRepair.normalizeHtml(html);
    }
}
