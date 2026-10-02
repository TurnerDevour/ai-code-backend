package com.example.aicodebackend.ai;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * AI 代码生成器服务接口
 */
public interface AiCodeGeneratorService {

    /**
     * 生成HTML代码
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的原始代码文本（Markdown 代码块），由调用方负责解析
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HTMLCodeResult generateHTMLCode(@UserMessage String prompt);

    /**
     * 生成多文件代码
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的原始代码文本（Markdown 代码块），由调用方负责解析
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultipleFileCode(@UserMessage String prompt);

    /**
     * 生成HTML代码流
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的代码流
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHTMLCodeStream(@UserMessage String prompt);

    /**
     * 生成多文件代码流
     *
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的多文件代码流
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultipleFileCodeStream(@UserMessage String prompt);
}
