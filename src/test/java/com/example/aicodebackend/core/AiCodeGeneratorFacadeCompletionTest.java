package com.example.aicodebackend.core;

import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.StubTokenStream;
import com.example.aicodebackend.config.AiCodeGeneratorServiceFactory;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多文件模式「生成结果自检 + 一次自动修复」的测试
 * <p>
 * 覆盖三类实测问题：
 * <ol>
 *     <li>模型只输出 {@code ```html} 一个代码块，style.css / script.js 整个缺失；</li>
 *     <li>模型把代码里的行内空格全部吞掉（{@code <divclass="box">}、{@code padding:024px}、
 *     {@code varheader=null;}、{@code functionsetYear(){}）——HTML 可以纯文本修复，
 *     CSS / JavaScript 必须让模型按原内容补回空格；</li>
 *     <li>模型返回的是结构化 JSON（{@code {"htmlCode":"…"}}）而不是代码块。</li>
 * </ol>
 * 不启动 Spring 容器、不调用真实模型：用桩服务记录"修复请求"，验证落盘内容与采纳规则。
 */
class AiCodeGeneratorFacadeCompletionTest {

    /** 丢空格的 HTML（标签名与属性粘连，正文与缩进正常） */
    private static final String GLUED_HTML = """
            <!DOCTYPEhtml>
            <htmllang="zh-CN">
            <head>
              <metacharset="UTF-8">
              <linkrel="stylesheet"href="style.css">
            </head>
            <body>
              <divclass="containerhero-inner">首页</div>
              <scriptsrc="script.js"></script>
            </body>
            </html>
            """;

    /** 上面那份 HTML 补回空格后的样子 */
    private static final String CLEAN_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <link rel="stylesheet" href="style.css">
            </head>
            <body>
              <div class="container hero-inner">首页</div>
              <script src="script.js"></script>
            </body>
            </html>
            """;

    /** 丢空格的 CSS（选择器本身完好，便于验证"类名粘连"检查） */
    private static final String GLUED_CSS = """
            .container{
            width:100%;
            }
            .hero-inner{
            display:flex;
            }
            :root{
              --shadow-sm:04px16pxrgba(15,27,45,.06);
            }
            body{
              margin:0auto;
            }
            """;

    /** 上面那份 CSS 补回空格后的样子（模型修复后的标准输出） */
    private static final String CLEAN_CSS = """
            .container {
              width: 100%;
            }
            .hero-inner {
              display: flex;
            }
            :root {
              --shadow-sm: 0 4px 16px rgba(15, 27, 45, .06);
            }
            body {
              margin: 0 auto;
            }
            """;

    /** 丢空格的 JavaScript */
    private static final String GLUED_JS = """
            (function(){
            'usestrict';
            vartimer=null;
            functionsetYear(){
              if(el)el.textContent=newDate().getFullYear();
              returnfalse;
            }
            })();
            """;

    /** 上面那份 JavaScript 补回空格后的样子 */
    private static final String CLEAN_JS = """
            (function () {
              'use strict';
              var timer = null;
              function setYear() {
                if (el) el.textContent = new Date().getFullYear();
                return false;
              }
            })();
            """;

    /** 真实形态：结构化 JSON 答案 + 整轮丢空格（换行与缩进还在，行内空格全没了） */
    private static final String GLUED_JSON_ANSWER = "{\n"
            + "  \"htmlCode\": " + jsonString(GLUED_HTML) + ",\n"
            + "  \"cssCode\": " + jsonString(GLUED_CSS) + ",\n"
            + "  \"jsCode\": " + jsonString(GLUED_JS) + ",\n"
            + "  \"description\": \"本次输出为演示网站的完整三文件实现。\"\n"
            + "}";

    /**
     * 记录修复请求并返回预置结果的桩服务
     */
    private static class StubAiCodeGeneratorService implements AiCodeGeneratorService {
        private final String streamResponse;
        private final String repairResponse;
        final List<String> repairPrompts = new ArrayList<>();

        /** 本轮要下发的思考增量（模拟推理模型的 reasoning_content） */
        final List<String> thinkingChunks = new ArrayList<>();

        StubAiCodeGeneratorService(String streamResponse, String repairResponse) {
            this.streamResponse = streamResponse;
            this.repairResponse = repairResponse;
        }

        @Override
        public TokenStream generateHTMLCodeStream(String prompt) {
            return new StubTokenStream(List.of(streamResponse), thinkingChunks);
        }

        @Override
        public TokenStream generateMultipleFileCodeStream(String prompt) {
            // 按模型真实行为分片（分片边界不能影响解析结果）
            List<String> chunks = new ArrayList<>();
            int step = 64;
            for (int i = 0; i < streamResponse.length(); i += step) {
                chunks.add(streamResponse.substring(i, Math.min(streamResponse.length(), i + step)));
            }
            return new StubTokenStream(chunks, thinkingChunks);
        }

        @Override
        public TokenStream generateVueProjectCodeStream(long appId, String prompt) {
            throw new UnsupportedOperationException("本测试不涉及 Vue 工程模式");
        }

        @Override
        public Flux<String> repairGeneratedCodeStream(String prompt) {
            repairPrompts.add(prompt);
            List<String> chunks = new ArrayList<>();
            int step = 37;
            for (int i = 0; i < repairResponse.length(); i += step) {
                chunks.add(repairResponse.substring(i, Math.min(repairResponse.length(), i + step)));
            }
            return Flux.fromIterable(chunks);
        }
    }

    /**
     * 只替换"创建服务"这一步的工厂子类：用桩服务代替真实模型调用
     */
    private static class StubFactory extends AiCodeGeneratorServiceFactory {
        private final AiCodeGeneratorService stub;

        StubFactory(AiCodeGeneratorService stub) {
            super(null, null, null, null);
            this.stub = stub;
        }

        @Override
        protected AiCodeGeneratorService createAiCodeGeneratorService(long appId,
                                                                     CodeGenTypeEnum codeGenTypeEnum,
                                                                     AIModelTypeEnum aiModelTypeEnum) {
            return stub;
        }
    }

    /** 模型只输出 HTML 代码块：应补齐 CSS 与 JS 并落盘 */
    @Test
    void shouldCompleteMissingCssAndJsAndSaveFiles(@TempDir Path tempRoot) throws Exception {
        String modelOutput = "这是为您生成的暗黑话题社区HTML代码。\n```html\n" + GLUED_HTML + "```\n页面包含导航与话题列表。\n";
        String repairResponse = fenced("css", CLEAN_CSS) + fenced("javascript", CLEAN_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, repairResponse);

        long appId = 990001L;
        File dir = productDir(appId);
        try {
            facadeWith(stub).generateAndSaveCodeStream("暗黑话题社区", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertEquals(1, stub.repairPrompts.size(), "缺少 CSS/JS 时应发起一次补全请求");
            String instruction = stub.repairPrompts.get(0);
            assertTrue(instruction.contains("style.css"), "补全指令要指明缺失文件: " + instruction);
            assertTrue(instruction.contains("script.js"));
            assertTrue(instruction.contains("暗黑话题社区"), "补全指令要带原始需求");
            assertTrue(instruction.contains("参考"), "index.html 应作为参考给出，保证类名对得上");

            String html = read(dir, "index.html");
            assertTrue(html.contains("<link rel=\"stylesheet\" href=\"style.css\">"),
                    "粘连的 HTML 应被修复，否则 link 不生效: " + html.substring(0, Math.min(200, html.length())));
            assertTrue(html.contains("<script src=\"script.js\"></script>"), "script 引用应被修复");
            assertEquals(CLEAN_CSS.strip(), read(dir, "style.css").strip(), "style.css 应为补全内容");
            assertEquals(CLEAN_JS.strip(), read(dir, "script.js").strip(), "script.js 应为补全内容");
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 三个代码块齐全且干净时不应触发修复 */
    @Test
    void shouldNotRepairWhenAllFilesPresentAndClean(@TempDir Path tempRoot) throws Exception {
        String modelOutput = fenced("html", CLEAN_HTML) + fenced("css", CLEAN_CSS) + fenced("javascript", CLEAN_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, "");

        long appId = 990002L;
        File dir = productDir(appId);
        try {
            facadeWith(stub).generateAndSaveCodeStream("博客首页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();
            assertEquals(0, stub.repairPrompts.size(), "文件齐全且没有丢空格时不应发起修复请求");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 结构化 JSON 答案 + 整轮丢空格：既要按字段拆出三个文件，
     * 也要把受损的 CSS / JavaScript 用一次修复调用换回干净版本
     */
    @Test
    void shouldRepairWhitespaceDamagedFilesFromStructuredAnswer(@TempDir Path tempRoot) throws Exception {
        String repairResponse = fenced("html", CLEAN_HTML) + fenced("css", CLEAN_CSS) + fenced("javascript", CLEAN_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(GLUED_JSON_ANSWER, repairResponse);

        long appId = 990003L;
        File dir = productDir(appId);
        try {
            facadeWith(stub).generateAndSaveCodeStream("企业官网", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertEquals(1, stub.repairPrompts.size(), "检测到丢空格时应发起一次修复请求");
            String instruction = stub.repairPrompts.get(0);
            assertTrue(instruction.contains("丢了空格"), "修复指令要说明问题: " + instruction);
            assertTrue(instruction.contains("--shadow-sm:04px16pxrgba"),
                    "修复指令要带上受损内容（让模型按原内容补空格，而不是重写一遍）");
            assertTrue(instruction.contains("class=\"containerhero-inner\""),
                    "类名被粘起来的问题要写进指令，否则模型不知道要拆开: " + instruction);

            String html = read(dir, "index.html");
            assertTrue(html.startsWith("<!DOCTYPE html>"), "HTML 应从 JSON 取值里解码出来并修复: "
                    + html.substring(0, Math.min(80, html.length())));
            assertFalse(html.contains("cssCode"), "index.html 里不应出现 JSON 字段名");
            assertFalse(html.contains("\\\""), "HTML 里不应残留 JSON 转义: " + html);
            assertTrue(html.contains("class=\"container hero-inner\""),
                    "粘连的类名要被拆回两个类名，否则 CSS 选择器匹配不上: " + html);
            assertTrue(html.contains("<script src=\"script.js\"></script>"));
            assertEquals(CLEAN_CSS.strip(), read(dir, "style.css").strip(), "style.css 应换成修复后的干净版本");
            assertEquals(CLEAN_JS.strip(), read(dir, "script.js").strip(), "script.js 应换成修复后的干净版本");
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 修复结果依然粘连时：必须保留原内容（不能把结果改差），也不能抛异常 */
    @Test
    void shouldKeepOriginalWhenRepairIsNotBetter(@TempDir Path tempRoot) throws Exception {
        String modelOutput = fenced("html", CLEAN_HTML) + fenced("css", GLUED_CSS) + fenced("javascript", GLUED_JS);
        // 修复调用返回的还是粘连内容（修复本身也可能再次丢空格）
        String repairResponse = fenced("css", GLUED_CSS) + fenced("javascript", GLUED_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, repairResponse);

        long appId = 990004L;
        File dir = productDir(appId);
        try {
            facadeWith(stub).generateAndSaveCodeStream("登录页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertEquals(1, stub.repairPrompts.size());
            assertEquals(GLUED_CSS.strip(), read(dir, "style.css").strip(), "修复没有变好时必须保留原内容");
            assertEquals(GLUED_JS.strip(), read(dir, "script.js").strip(), "修复没有变好时必须保留原内容");
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 修复回复无法解析（模型只回了一段解释）时：保留原内容，不抛异常 */
    @Test
    void shouldKeepOriginalWhenRepairResponseIsNotParseable(@TempDir Path tempRoot) throws Exception {
        String modelOutput = fenced("html", CLEAN_HTML) + fenced("css", GLUED_CSS) + fenced("javascript", GLUED_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, "抱歉，我无法完成这次修复。");

        long appId = 990005L;
        File dir = productDir(appId);
        try {
            assertNotNull(facadeWith(stub)
                    .generateAndSaveCodeStream("登录页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block());
            assertEquals(1, stub.repairPrompts.size());
            assertEquals(GLUED_CSS.strip(), read(dir, "style.css").strip());
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 修复候选明显缩水（模型只回了一小段）时：必须丢弃，保留原内容 */
    @Test
    void shouldRejectRepairCandidateThatIsMuchShorter(@TempDir Path tempRoot) throws Exception {
        String modelOutput = fenced("html", CLEAN_HTML) + fenced("css", GLUED_CSS) + fenced("javascript", GLUED_JS);
        String repairResponse = fenced("css", "body { margin: 0 auto; }");
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, repairResponse);

        long appId = 990006L;
        File dir = productDir(appId);
        try {
            facadeWith(stub).generateAndSaveCodeStream("登录页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertEquals(GLUED_CSS.strip(), read(dir, "style.css").strip(),
                    "明显缩水的候选必须被丢弃（不能让模型用片段替换整份样式）");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 思考过程必须随流单独下发（{@code type=ai_thinking}），且不能混进正文与落盘文件
     * <p>
     * 回归背景（实测）：HTML / 多文件模式原来用 {@code Flux<String>} 接模型输出，langchain4j 的 Reactor
     * 适配器只接正文增量，思考内容（reasoning_content）在适配层就被丢掉了，前端「AI 思考过程」面板永远是空的。
     * 现在这两个模式也走 TokenStream，思考增量必须作为独立消息下发——同时正文与落盘内容都要保持"只有代码"。
     */
    @Test
    void shouldStreamThinkingSeparatelyFromCode(@TempDir Path tempRoot) throws Exception {
        String modelOutput = fenced("html", CLEAN_HTML) + fenced("css", CLEAN_CSS) + fenced("javascript", CLEAN_JS);
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, "");
        stub.thinkingChunks.add("先想一下页面结构");
        stub.thinkingChunks.add("再想一下配色");

        long appId = 990007L;
        File dir = productDir(appId);
        try {
            List<String> messages = facadeWith(stub)
                    .generateAndSaveCodeStream("博客首页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertNotNull(messages, "消息流不应为空");
            List<String> thinking = messages.stream()
                    .filter(message -> JSONUtil.parseObj(message).getStr("type") != null
                            && "ai_thinking".equals(JSONUtil.parseObj(message).getStr("type")))
                    .map(message -> JSONUtil.parseObj(message).getStr("data"))
                    .toList();
            assertEquals(List.of("先想一下页面结构", "再想一下配色"), thinking,
                    "思考增量必须以 ai_thinking 消息单独下发（前端据此渲染思考面板）");

            String content = messages.stream()
                    .filter(message -> "ai_response".equals(JSONUtil.parseObj(message).getStr("type")))
                    .map(message -> JSONUtil.parseObj(message).getStr("data"))
                    .reduce("", String::concat);
            assertTrue(content.contains("```html"), "正文增量应与模型输出一致: " + content.substring(0, Math.min(80, content.length())));
            assertFalse(content.contains("先想一下页面结构"), "思考内容不能混进正文，否则代码解析与对话历史都会被污染");

            assertFalse(read(dir, "index.html").contains("先想一下页面结构"), "落盘文件不能带上推理内容");
            assertFalse(read(dir, "style.css").contains("先想一下页面结构"));
            assertFalse(read(dir, "script.js").contains("先想一下页面结构"));
        } finally {
            deleteQuietly(dir);
        }
    }

    private static AiCodeGeneratorFacade facadeWith(AiCodeGeneratorService stub) {
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", new StubFactory(stub));
        return facade;
    }

    private static File productDir(long appId) {
        return new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "multi_file_" + appId);
    }

    private static String read(File dir, String name) throws Exception {
        return Files.readString(new File(dir, name).toPath(), StandardCharsets.UTF_8);
    }

    /** 包一个 Markdown 代码块 */
    private static String fenced(String language, String content) {
        return "```" + language + "\n" + content + "```\n";
    }

    /** 把文本转成 JSON 字符串字面量（测试里用真实形态的转义，而不是手写转义） */
    private static String jsonString(String text) {
        return '"' + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteQuietly(child);
            }
        }
        // 删不掉（被占用等）时忽略，不污染测试结果
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
