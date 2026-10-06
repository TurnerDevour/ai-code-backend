package com.example.aicodebackend.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.ai.model.message.AiResponseMessage;
import com.example.aicodebackend.ai.model.message.ToolExecutedMessage;
import com.example.aicodebackend.ai.model.message.ToolRequestMessage;
import com.example.aicodebackend.config.AiCodeGeneratorServiceFactory;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.parser.CodeParserExecutor;
import com.example.aicodebackend.saver.CodeFileSaverExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AiCodeGeneratorFacade 是一个外观类，用于简化与 AI 代码生成器的交互。
 * 该类提供了统一的接口，封装了复杂的代码生成逻辑，使客户端可以更方便地使用 AI 代码生成功能。
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 根据给定的提示和代码生成类型生成代码，并将其保存到文件中（非流式输出）。
     *
     * @param prompt          用户提供的提示，用于指导代码生成。
     * @param codeGenTypeEnum 代码生成类型枚举，指定生成 HTML 代码或多文件代码。
     * @param appId           应用 id
     * @param aiModelTypeEnum AI 模型类型枚举，为空时使用默认模型
     *
     * @return 生成的代码文件。
     *
     * @throws BusinessException 如果 codeGenTypeEnum 为 null，则抛出参数错误异常。
     */
    public File generateAndSaveCode(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId, AIModelTypeEnum aiModelTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum, aiModelTypeEnum);
                HTMLCodeResult htmlCodeResult = aiCodeGeneratorService.generateHTMLCode(prompt);
                yield CodeFileSaverExecutor.executeSaver(htmlCodeResult, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum, aiModelTypeEnum);
                MultiFileCodeResult multiFileResult = aiCodeGeneratorService.generateMultipleFileCode(prompt);
                yield CodeFileSaverExecutor.executeSaver(multiFileResult, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unexpected value: " + codeGenTypeEnum);
        };
    }

    /**
     * 根据给定的提示和代码生成类型生成代码，并将其保存到文件中（非流式输出，使用默认模型）。
     */
    public File generateAndSaveCode(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCode(prompt, codeGenTypeEnum, appId, AIModelTypeEnum.DEEPSEEK_FLASH);
    }

    /**
     * 根据给定的提示和代码生成类型生成代码，并以流式方式返回生成的代码。
     *
     * @param prompt          用户提供的提示，用于指导代码生成。
     * @param codeGenTypeEnum 代码生成类型枚举，指定生成 HTML 代码或多文件代码。
     * @param appId           应用 id
     * @param aiModelTypeEnum AI 模型类型枚举，为空时使用默认模型
     *
     * @return 生成的代码流。
     *
     * @throws BusinessException 如果 codeGenTypeEnum 为 null，则抛出参数错误异常。
     */
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId, AIModelTypeEnum aiModelTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum, aiModelTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHTMLCodeStream(prompt);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId, aiCodeGeneratorService, prompt);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultipleFileCodeStream(prompt);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId, aiCodeGeneratorService, prompt);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, prompt);
                yield processCodeTokenStream(tokenStream);
            }
        };
    }

    /**
     * 流式生成代码（使用默认模型）
     */
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(prompt, codeGenTypeEnum, appId, AIModelTypeEnum.DEEPSEEK_FLASH);
    }

    /**
     * 处理 TokenStream（VUE_PROJECT 模式），把模型输出、思考过程与工具调用转换为前端可消费的 JSON 消息流。
     * <p>
     * VUE_PROJECT 使用推理模型，模型在调用文件写入工具之前只会输出思考内容（reasoning_content），
     * 不会产生正常的文本增量。因此必须监听 onPartialThinking，否则前端在整个代码生成阶段收不到任何内容。
     *
     * @param tokenStream 生成的代码流。
     *
     * @return 处理后的代码流。
     */
    private Flux<String> processCodeTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            tokenStream.onPartialResponse(partialResponse -> {
                log.info("VUE_PROJECT 文本增量: {}", StrUtil.maxLength(partialResponse, 200));
                AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                sink.next(JSONUtil.toJsonStr(aiResponseMessage));
            }).onPartialThinking(partialThinking -> {
                String thinking = partialThinking.text();
                if (StrUtil.isBlank(thinking)) {
                    return;
                }
                // 思考过程也作为 AI 响应推送，保证代码生成期间前端持续有流式输出
                AiResponseMessage aiResponseMessage = new AiResponseMessage(thinking);
                sink.next(JSONUtil.toJsonStr(aiResponseMessage));
            }).onToolExecuted(toolExecution -> {
                ToolExecutionRequest request = toolExecution.request();
                ToolRequestMessage toolRequestMessage = new ToolRequestMessage(request);
                sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
            }).onCompleteResponse(chatResponse -> {
                log.info("VUE_PROJECT 生成完成，结束原因: {}", chatResponse.finishReason());
                sink.complete();
            }).onError(throwable -> {
                log.error("处理代码流时发生错误", throwable);
                sink.error(throwable);
            }).start();
        });
    }

    /**
     * 处理代码流，将生成的代码保存到文件中。
     * <p>
     * 实测背景：HTML / 多文件模式下，模型并不总是遵守系统提示词——多文件模式经常只输出
     * {@code index.html} 一个代码块（有时连 CSS 一起漏掉），此时产物目录里没有 style.css / script.js，
     * 预览自然"没有样式、没有交互"。这里在流结束、落盘之前做一次补全：
     * 先把流内容累积下来，解析后发现缺少文件就用一次非流式生成把缺失部分补齐并一起落盘。
     * <p>
     * 累积仍然内联在这条链上（写的是 StringBuilder，不需要客户端在线）；
     * 客户端断开时 Reactor 会取消这条链，{@code doOnComplete} 不再触发，与原有语义一致。
     *
     * @param codeStream          生成的代码流
     * @param codeGenTypeEnum     代码生成类型枚举，指定生成 HTML 代码或多文件代码
     * @param appId               应用 id
     * @param aiCodeGeneratorService 生成服务（补全时复用同一个实例，保持对话记忆）
     * @param prompt              用户本轮提示词（补全时需要知道原始需求）
     *
     * @return 处理后的代码流。
     */
    private Flux<String> processCodeStream(Flux<String> codeStream,
                                          CodeGenTypeEnum codeGenTypeEnum,
                                          Long appId,
                                          AiCodeGeneratorService aiCodeGeneratorService,
                                          String prompt) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parserResult = CodeParserExecutor.executorParser(completeCode, codeGenTypeEnum);
                        parserResult = completeMissingFiles(parserResult, codeGenTypeEnum, aiCodeGeneratorService, prompt);
                        File saveDir = CodeFileSaverExecutor.executeSaver(parserResult, codeGenTypeEnum, appId);
                        log.info("{}代码已保存到文件: {}", codeGenTypeEnum, saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存代码到文件失败", e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存代码到文件失败");
                    }
                });
    }

    /**
     * 多文件模式的补全重试：模型漏输出 CSS / JavaScript 代码块时，用一次非流式调用补齐
     * <p>
     * 为什么必须补：{@code index.html} 里已经有 {@code <link href="style.css">} 与
     * {@code <script src="script.js">}，文件缺失时页面就是"裸 HTML"，用户看到的就是
     * "没有生成 CSS 和 JS"。让前端再让用户手动追问一轮，不如在服务端补一次。
     * <p>
     * 补全失败只记日志：宁可用已有内容落盘（用户可再追问），也不要让整轮生成失败。
     *
     * @param parserResult           已解析的生成结果
     * @param codeGenTypeEnum        代码生成类型
     * @param aiCodeGeneratorService 生成服务
     * @param prompt                 用户本轮提示词
     *
     * @return 补齐后的结果（无需补全时原样返回）
     */
    private Object completeMissingFiles(Object parserResult,
                                        CodeGenTypeEnum codeGenTypeEnum,
                                        AiCodeGeneratorService aiCodeGeneratorService,
                                        String prompt) {
        if (codeGenTypeEnum != CodeGenTypeEnum.MULTI_FILE || !(parserResult instanceof MultiFileCodeResult multiFile)) {
            return parserResult;
        }
        boolean missingCss = StrUtil.isBlank(multiFile.getCssCode());
        boolean missingJs = StrUtil.isBlank(multiFile.getJsCode());
        if (!missingCss && !missingJs) {
            return parserResult;
        }
        log.warn("多文件模式本次输出缺少代码块（css={}, js={}），尝试补全", !missingCss, !missingJs);
        StringBuilder instruction = new StringBuilder();
        instruction.append("你上一次的回复不符合要求：");
        if (missingCss) {
            instruction.append("缺少 ```css 代码块（style.css）");
        }
        if (missingCss && missingJs) {
            instruction.append("、");
        }
        if (missingJs) {
            instruction.append("缺少 ```javascript 代码块（script.js）");
        }
        instruction.append("。\n原始需求：").append(StrUtil.nullToEmpty(prompt));
        instruction.append("\n请严格按要求补齐：只输出缺失的代码块本身（```css ... ``` 与 ```javascript ... ```），")
                .append("针对上面这个页面的完整实现，不要输出 index.html，不要有任何解释文字。");
        try {
            MultiFileCodeResult completion = aiCodeGeneratorService.generateMultipleFileCode(instruction.toString());
            if (completion == null) {
                return parserResult;
            }
            if (missingCss && StrUtil.isNotBlank(completion.getCssCode())) {
                multiFile.setCssCode(completion.getCssCode().trim());
            }
            if (missingJs && StrUtil.isNotBlank(completion.getJsCode())) {
                multiFile.setJsCode(completion.getJsCode().trim());
            }
            log.info("多文件补全完成：css={}, js={}",
                    StrUtil.isNotBlank(multiFile.getCssCode()), StrUtil.isNotBlank(multiFile.getJsCode()));
        } catch (Exception e) {
            log.warn("多文件补全失败（按已有内容落盘，用户可再追问）：{}", e.getMessage());
        }
        return parserResult;
    }
}
