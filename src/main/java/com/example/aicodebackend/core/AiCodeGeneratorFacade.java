package com.example.aicodebackend.core;

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
     *
     * @return 生成的代码文件。
     *
     * @throws BusinessException 如果 codeGenTypeEnum 为 null，则抛出参数错误异常。
     */
    public File generateAndSaveCode(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                HTMLCodeResult htmlCodeResult = aiCodeGeneratorService.generateHTMLCode(prompt);
                yield CodeFileSaverExecutor.executeSaver(htmlCodeResult, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                MultiFileCodeResult multiFileResult = aiCodeGeneratorService.generateMultipleFileCode(prompt);
                yield CodeFileSaverExecutor.executeSaver(multiFileResult, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unexpected value: " + codeGenTypeEnum);
        };
    }

    /**
     * 根据给定的提示和代码生成类型生成代码，并以流式方式返回生成的代码。
     *
     * @param prompt          用户提供的提示，用于指导代码生成。
     * @param codeGenTypeEnum 代码生成类型枚举，指定生成 HTML 代码或多文件代码。
     *
     * @return 生成的代码流。
     *
     * @throws BusinessException 如果 codeGenTypeEnum 为 null，则抛出参数错误异常。
     */
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHTMLCodeStream(prompt);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultipleFileCodeStream(prompt);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, prompt);
                yield processCodeTokenStream(tokenStream);
            }
        };
    }

    /**
     * 处理代码流，将生成的代码保存到文件中。
     *
     * @param tokenStream 生成的代码流。
     *
     * @return 处理后的代码流。
     */
    private Flux<String> processCodeTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            tokenStream.onPartialResponse(partialResponse -> {
                AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                sink.next(JSONUtil.toJsonStr(aiResponseMessage));
            }).onToolExecuted(toolExecution -> {
                ToolExecutionRequest request = toolExecution.request();
                ToolRequestMessage toolRequestMessage = new ToolRequestMessage(request);
                sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
            }).onCompleteResponse(chatResponse -> {
                sink.complete();
            }).onError(throwable -> {
                log.error("处理代码流时发生错误", throwable);
                sink.error(throwable);
            }).start();
        });
    }

    /**
     * 处理代码流，将生成的代码保存到文件中。
     *
     * @param codeStream      生成的代码流。
     * @param codeGenTypeEnum 代码生成类型枚举，指定生成 HTML 代码或多文件代码。
     *
     * @return 处理后的代码流。
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parserResult = CodeParserExecutor.executorParser(completeCode, codeGenTypeEnum);
                        File saveDir = CodeFileSaverExecutor.executeSaver(parserResult, codeGenTypeEnum, appId);
                        log.info("{}代码已保存到文件: {}", codeGenTypeEnum, saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存代码到文件失败", e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存代码到文件失败");
                    }
                });
    }
}
