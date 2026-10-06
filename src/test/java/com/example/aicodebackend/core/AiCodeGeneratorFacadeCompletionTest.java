package com.example.aicodebackend.core;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多文件模式「缺少 CSS / JavaScript 代码块」的服务端补齐测试
 * <p>
 * 实测场景：模型只输出 {@code ```html} 一个代码块（有时连 CSS 一起漏掉），
 * 产物目录里没有 style.css / script.js，预览表现为"没有样式、没有交互"。
 * 这里用桩服务模拟该输出，验证门面层会用一次非流式生成把缺失文件补齐并落盘。
 * <p>
 * 不启动 Spring 容器、不调用真实模型。
 */
class AiCodeGeneratorFacadeCompletionTest {

    /**
     * 记录调用并返回预置结果的桩服务
     */
    private static class StubAiCodeGeneratorService implements AiCodeGeneratorService {
        private final String streamResponse;
        private final MultiFileCodeResult completionResponse;
        final List<String> completionPrompts = new ArrayList<>();

        StubAiCodeGeneratorService(String streamResponse, MultiFileCodeResult completionResponse) {
            this.streamResponse = streamResponse;
            this.completionResponse = completionResponse;
        }

        @Override
        public HTMLCodeResult generateHTMLCode(String prompt) {
            return null;
        }

        @Override
        public MultiFileCodeResult generateMultipleFileCode(String prompt) {
            completionPrompts.add(prompt);
            return completionResponse;
        }

        @Override
        public Flux<String> generateHTMLCodeStream(String prompt) {
            return Flux.just(streamResponse);
        }

        @Override
        public Flux<String> generateMultipleFileCodeStream(String prompt) {
            // 按模型真实行为分片（分片边界不能影响解析结果）
            List<String> chunks = new ArrayList<>();
            int step = 64;
            for (int i = 0; i < streamResponse.length(); i += step) {
                chunks.add(streamResponse.substring(i, Math.min(streamResponse.length(), i + step)));
            }
            return Flux.fromIterable(chunks);
        }

        @Override
        public TokenStream generateVueProjectCodeStream(long appId, String prompt) {
            throw new UnsupportedOperationException("本测试不涉及 Vue 工程模式");
        }
    }

    /**
     * 只替换"创建服务"这一步的工厂子类：用桩服务代替真实模型调用
     */
    private static class StubFactory extends AiCodeGeneratorServiceFactory {
        private final AiCodeGeneratorService stub;

        StubFactory(AiCodeGeneratorService stub) {
            super(null, null, null, null, null);
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
        String modelOutput = """
                这是为您生成的暗黑话题社区HTML代码。
                ```html
                <!DOCTYPEhtml>
                <htmllang="zh-CN">
                <head><linkrel="stylesheet"href="style.css"></head>
                <body><scriptsrc="script.js"></script></body>
                </html>
                ```
                页面包含导航、话题列表与发帖弹窗。
                """;
        MultiFileCodeResult completion = new MultiFileCodeResult();
        completion.setCssCode("body { background: #111; }");
        completion.setJsCode("console.log('ready')");
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, completion);

        long appId = 990001L;
        AiCodeGeneratorServiceFactory factory = new StubFactory(stub);

        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);

        String originalRoot = AppConstant.CODE_OUTPUT_ROOT_DIR;
        try {
            // 产物目录固定在 AppConstant（常量，不能注入）：这里通过目录名做隔离，
            // 只断言该 appId 对应目录里的文件内容，不影响其它用例
            List<String> chunks = facade
                    .generateAndSaveCodeStream("暗黑话题社区", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList()
                    .block();
            assertNotNull(chunks);

            assertEquals(1, stub.completionPrompts.size(), "缺少 CSS/JS 时应发起一次补全请求");
            String instruction = stub.completionPrompts.get(0);
            assertTrue(instruction.contains("style.css"), "补全指令要指明缺失文件: " + instruction);
            assertTrue(instruction.contains("script.js"));
            assertTrue(instruction.contains("暗黑话题社区"), "补全指令要带原始需求");

            File dir = new File(originalRoot + File.separator + "multi_file_" + appId);
            assertTrue(dir.isDirectory(), "产物目录应已创建: " + dir.getAbsolutePath());
            String html = Files.readString(new File(dir, "index.html").toPath(), StandardCharsets.UTF_8);
            String css = Files.readString(new File(dir, "style.css").toPath(), StandardCharsets.UTF_8);
            String js = Files.readString(new File(dir, "script.js").toPath(), StandardCharsets.UTF_8);

            assertTrue(html.contains("<link rel=\"stylesheet\" href=\"style.css\">"),
                    "粘连的 HTML 应被修复，否则 link 不生效: " + html.substring(0, Math.min(200, html.length())));
            assertTrue(html.contains("<script src=\"script.js\"></script>"), "script 引用应被修复");
            assertEquals("body { background: #111; }", css, "style.css 应为补全内容");
            assertEquals("console.log('ready')", js, "script.js 应为补全内容");
        } finally {
            deleteQuietly(new File(originalRoot + File.separator + "multi_file_" + appId));
        }
    }

    /** 三个代码块齐全时不应触发补全 */
    @Test
    void shouldNotCompleteWhenAllFilesPresent() throws Exception {
        String modelOutput = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN"><head><link rel="stylesheet" href="style.css"></head>
                <body><script src="script.js"></script></body></html>
                ```
                ```css
                body { margin: 0; }
                ```
                ```javascript
                console.log(1)
                ```
                """;
        StubAiCodeGeneratorService stub = new StubAiCodeGeneratorService(modelOutput, new MultiFileCodeResult());

        long appId = 990002L;
        AiCodeGeneratorServiceFactory factory = new StubFactory(stub);

        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);

        try {
            facade.generateAndSaveCodeStream("博客首页", CodeGenTypeEnum.MULTI_FILE, appId).collectList().block();
            assertEquals(0, stub.completionPrompts.size(), "文件齐全时不应发起补全请求");
        } finally {
            deleteQuietly(new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "multi_file_" + appId));
        }
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
