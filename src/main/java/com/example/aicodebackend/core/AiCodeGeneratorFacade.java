package com.example.aicodebackend.core;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * AiCodeGeneratorFacade 是一个外观类，用于简化与 AI 代码生成器的交互。
 * 该类提供了统一的接口，封装了复杂的代码生成逻辑，使客户端可以更方便地使用 AI 代码生成功能。
 */
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    public File generateAndSaveCode(String prompt, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> generateAndSaveHTMLCode(prompt);
            case MULTI_FILE -> generateAndSaveMultiFileCode(prompt);
        };
    }

    // 生成HTML代码并保存到文件
    public File generateAndSaveHTMLCode(String prompt) {
        return CodeFileSaver.saveHtmlCodeToFile(aiCodeGeneratorService.generateHTMLCode(prompt));
    }

    // 生成多文件代码并保存到文件
    public File generateAndSaveMultiFileCode(String prompt) {
        return CodeFileSaver.saveMultiFileCodeToFile(aiCodeGeneratorService.generateMultipleFileCode(prompt));
    }
}
