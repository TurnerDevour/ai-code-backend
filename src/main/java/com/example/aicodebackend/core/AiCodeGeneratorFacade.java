package com.example.aicodebackend.core;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
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
    private AiCodeGeneratorService aiCodeGeneratorService;

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
    public File generateAndSaveCode(String prompt, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHTMLCode(prompt);
            case MULTI_FILE -> generateAndSaveMultiFileCode(prompt);
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
    public Flux<String> generateAndSaveCodeStream(String prompt, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHTMLCodeStream(prompt);
            case MULTI_FILE -> generateAndSaveMultiFileCodeStream(prompt);
        };
    }


    // 根据给定的提示和代码生成类型生成代码，并以流式方式返回生成的代码。
    private Flux<String> generateAndSaveHTMLCodeStream(String prompt) {
        Flux<String> result = aiCodeGeneratorService.generateHTMLCodeStream(prompt);
        // 当流式输出完成后，将生成的代码保存到文件中
        StringBuilder codeBuilder = new StringBuilder();
        return result.
                doOnNext(codeBuilder::append) // 将每个块追加到 StringBuilder 中
                .doOnComplete(() -> { // 流式输出完成后，将生成的代码保存到文件中
                    try {
                        String completeHTMLCode = codeBuilder.toString();
                        HTMLCodeResult htmlCodeResult = CodeParser.parseHtmlCode(completeHTMLCode);
                        File saveDir = CodeFileSaver.saveHtmlCodeToFile(htmlCodeResult);
                        log.info("HTML代码已保存到文件: {}", saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存HTML代码到文件失败", e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存HTML代码到文件失败");
                    }
                });
    }

    // 根据给定的提示和代码生成类型生成多文件代码，并以流式方式返回生成的代码。
    private Flux<String> generateAndSaveMultiFileCodeStream(String prompt) {
        Flux<String> result = aiCodeGeneratorService.generateMultipleFileCodeStream(prompt);
        // 当流式输出完成后，将生成的代码保存到文件中
        StringBuilder codeBuilder = new StringBuilder();
        return result.
                doOnNext(codeBuilder::append) // 将每个块追加到 StringBuilder 中
                .doOnComplete(() -> { // 流式输出完成后，将生成的代码保存到文件中
                    try {
                        String completeMultiFileCode = codeBuilder.toString();
                        // 诊断日志：确认流式输出是否完整（是否包含 html/css/js 三个代码块）
                        log.info("代码内容：{}",completeMultiFileCode);
                        log.info("多文件代码流式输出完成，总长度={}, 含```html={}, 含```css={}, 含```js={}",
                                completeMultiFileCode.length(),
                                completeMultiFileCode.contains("```html"),
                                completeMultiFileCode.contains("```css"),
                                completeMultiFileCode.contains("```js") || completeMultiFileCode.contains("```javascript"));
                        log.info("多文件代码流式原文:\n{}", completeMultiFileCode);
                        MultiFileCodeResult multiFileResult = CodeParser.parseMultiFileCode(completeMultiFileCode);
                        File saveDir = CodeFileSaver.saveMultiFileCodeToFile(multiFileResult);
                        log.info("多文件代码已保存到文件: {}", saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存多文件代码到文件失败", e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存多文件代码到文件失败");
                    }
                });
    }

    // 生成HTML代码并保存到文件
    private File generateAndSaveHTMLCode(String prompt) {
        String rawCode = aiCodeGeneratorService.generateHTMLCode(prompt);
        return CodeFileSaver.saveHtmlCodeToFile(CodeParser.parseHtmlCode(rawCode));
    }

    // 生成多文件代码并保存到文件
    private File generateAndSaveMultiFileCode(String prompt) {
        String rawCode = aiCodeGeneratorService.generateMultipleFileCode(prompt);
        return CodeFileSaver.saveMultiFileCodeToFile(CodeParser.parseMultiFileCode(rawCode));
    }
}
