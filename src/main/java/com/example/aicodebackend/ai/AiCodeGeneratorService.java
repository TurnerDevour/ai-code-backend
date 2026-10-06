package com.example.aicodebackend.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * AI 代码生成器服务接口
 * <p>
 * 本接口<b>只提供流式方法</b>：{@code AiServices} 只配置了 {@code StreamingChatModel}，
 * LangChain4j 对非流式返回类型（String、POJO）会去用阻塞式 {@code ChatModel}，而它没有被配置，
 * 调用会直接抛 {@code IllegalArgumentException: chatModel cannot be null}。
 */
public interface AiCodeGeneratorService {

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

    /**
     * 生成Vue项目代码流
     *
     * @param appId  应用ID
     * @param prompt 用户输入的提示信息
     *
     * @return 生成的Vue项目代码流
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String prompt);

    /**
     * 修复生成结果：把丢失空格的代码补回空格，或补齐缺失的文件
     * <p>
     * 返回 {@code Flux<String>}（流式）而不是 POJO / String 是刻意为之，原因有两点：
     * <ol>
     *     <li>本应用的 {@code AiServices} 只配置了 {@code StreamingChatModel}。LangChain4j 对<b>非流式</b>
     *     返回类型（String、POJO）会去用阻塞式 {@code ChatModel}，而它没有被配置，
     *     调用会直接抛 {@code IllegalArgumentException: chatModel cannot be null}；</li>
     *     <li>LangChain4j 只对 POJO 返回类型追加"必须严格按以下 JSON 格式回答"的要求，
     *     而把几万字符的代码塞进 JSON 字符串需要模型逐字符转义，本身就是新的出错来源。
     *     这里让模型按 Markdown 代码块回答，由调用方用代码块解析器取用，
     *     同时也不会把这套 JSON 格式要求写进共享的对话记忆里。</li>
     * </ol>
     *
     * @param prompt 修复指令（包含待修复的文件内容与问题说明）
     *
     * @return 模型输出的文本增量流（Markdown 代码块），调用方负责拼成完整文本
     */
    @SystemMessage(fromResource = "prompt/codegen-repair-system-prompt.txt")
    Flux<String> repairGeneratedCodeStream(@UserMessage String prompt);
}

