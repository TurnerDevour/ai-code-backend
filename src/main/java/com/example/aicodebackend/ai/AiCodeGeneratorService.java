package com.example.aicodebackend.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * AI 代码生成器服务接口
 * 本接口只提供流式方法：{@code AiServices} 只配置了 {@code StreamingChatModel}，
 * LangChain4j 对非流式返回类型（String、POJO）会去用阻塞式 {@code ChatModel}，而它没有被配置……
 */
public interface AiCodeGeneratorService {

    /**
     * 生成HTML代码流
     * 返回 {@link TokenStream} 而不是 {@code Flux<String>}：LangChain4j 的 Reactor 适配器
     * （{@code TokenStreamToFluxAdapter}）只把正文增量接进 Flux，模型的思考内容……
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    TokenStream generateHTMLCodeStream(@UserMessage String prompt);

    /**
     * 生成多文件代码流
     * 与 {@link #generateHTMLCodeStream(String)} 同理用 {@link TokenStream} 而不是 {@code Flux<String>}：
     * 否则思考内容会在 LangChain4j 的 Reactor 适配层被丢掉。
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    TokenStream generateMultipleFileCodeStream(@UserMessage String prompt);

    /** 生成Vue项目代码流 */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String prompt);

    /**
     * 修复生成结果：把丢失空格的代码补回空格，或补齐缺失的文件
     * 返回 {@code Flux<String>}（流式）而不是 POJO / String 是刻意为之，原因有两点：
     * 返回类型（String、POJO）会去用阻塞式 {@code ChatModel}，而它没有被配置……
     */
    @SystemMessage(fromResource = "prompt/codegen-repair-system-prompt.txt")
    Flux<String> repairGeneratedCodeStream(@UserMessage String prompt);
}
