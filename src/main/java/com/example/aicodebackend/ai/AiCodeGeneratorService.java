package com.example.aicodebackend.ai;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.SystemMessage;

/**
 * AI 代码生成器服务接口
 */
public interface AiCodeGeneratorService {

    /**
     * 生成HTML代码
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的代码
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HTMLCodeResult generateHTMLCode(String prompt);

    /**
     * 生成多文件代码
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的多文件代码
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultipleFileCode(String prompt);
}
