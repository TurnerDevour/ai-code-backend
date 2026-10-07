package com.example.aicodebackend.core;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.config.AiCodeGeneratorServiceFactory;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式生成链路必须真的能通过 LangChain4j 调起来
 * <p>
 * 背景（实测事故）：本应用的 {@code AiServices} 只配置了 {@link StreamingChatModel}（没有阻塞式
 * {@code ChatModel}），而 LangChain4j 对<b>非流式</b>返回类型会去用阻塞模型，直接抛
 * {@code IllegalArgumentException: chatModel cannot be null}。因此接口里的方法必须全部是流式的，
 * 否则「自动修复」在生产里每次都会静默失败（只留下一条日志），坏代码照样落盘。
 * <p>
 * 这里用一个假流式模型把整条链路（AiServices 代理 → 系统提示词资源 → 流式聚合 → 解析 → 落盘）跑通，
 * 不依赖真实模型与 API Key。
 */
class AiCodeGeneratorFacadeRepairModelTest {

    /** 按顺序回预置文本的假流式模型（第一次调用=生成，之后=修复） */
    private static class FakeStreamingChatModel implements StreamingChatModel {

        private final List<String> replies;
        private final AtomicInteger calls = new AtomicInteger();

        FakeStreamingChatModel(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            int index = Math.min(calls.getAndIncrement(), replies.size() - 1);
            String reply = replies.get(index);
            // 按真实模型的行为分片返回
            int step = 29;
            for (int i = 0; i < reply.length(); i += step) {
                handler.onPartialResponse(reply.substring(i, Math.min(reply.length(), i + step)));
            }
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
        }
    }

    private static class StubFactory extends AiCodeGeneratorServiceFactory {
        private final AiCodeGeneratorService service;

        StubFactory(AiCodeGeneratorService service) {
            super(null, null, null, null);
            this.service = service;
        }

        @Override
        protected AiCodeGeneratorService createAiCodeGeneratorService(long appId,
                                                                     CodeGenTypeEnum codeGenTypeEnum,
                                                                     AIModelTypeEnum aiModelTypeEnum) {
            return service;
        }
    }

    /**
     * 用假流式模型构建 AiServices 代理
     * <p>
     * 必须配 ChatMemoryProvider：接口里 {@code generateVueProjectCodeStream} 带 {@code @MemoryId}，
     * 不配的话 AiServices 在 build 阶段就会报 IllegalConfiguration。
     */
    private static AiCodeGeneratorService serviceWith(String... replies) {
        return AiServices.builder(AiCodeGeneratorService.class)
                .streamingChatModel(new FakeStreamingChatModel(replies))
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /**
     * 直接用 AiServices 代理验证：流式修复方法在"只有流式模型"的配置下可以正常返回内容
     */
    @Test
    void repairMethodShouldWorkThroughAiServicesWithStreamingModelOnly() {
        AiCodeGeneratorService service = serviceWith("```css\nbody { margin: 0; }\n```\n");

        List<String> chunks = service.repairGeneratedCodeStream("修复：body{\nmargin:0;\n}").collectList().block();

        assertNotNull(chunks);
        assertEquals("```css\nbody { margin: 0; }\n```\n", String.join("", chunks));
    }

    /**
     * 端到端：流里是丢空格的 CSS / JavaScript，修复调用（走真实的 AiServices 代理与系统提示词资源）
     * 返回干净版本，最终落盘的必须是修复后的内容
     */
    @Test
    void facadeShouldSaveRepairedFilesUsingRealAiServicesProxy() throws Exception {
        String streamResponse = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN"><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>你好</h1><script src="script.js"></script></body></html>
                ```
                ```css
                body{
                  margin:0auto;
                  padding:024px;
                }
                ```
                ```javascript
                (function(){
                'usestrict';
                vartimer=null;
                })();
                ```
                """;
        String repairReply = """
                ```css
                body {
                  margin: 0 auto;
                  padding: 0 24px;
                }
                ```
                ```javascript
                (function () {
                  'use strict';
                  var timer = null;
                })();
                ```
                """;

        long appId = 991001L;
        File dir = productDir(appId);
        try {
            facadeWith(serviceWith(streamResponse, repairReply))
                    .generateAndSaveCodeStream("测试页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block();

            assertEquals("body {\n  margin: 0 auto;\n  padding: 0 24px;\n}", read(dir, "style.css"),
                    "落盘的 CSS 必须是修复调用返回的干净版本（说明修复调用真的成功了）");
            assertEquals("(function () {\n  'use strict';\n  var timer = null;\n})();", read(dir, "script.js"));
            assertTrue(read(dir, "index.html").contains("<link rel=\"stylesheet\" href=\"style.css\">"));
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 修复回复无法解析时：不抛异常，按原内容落盘 */
    @Test
    void facadeShouldKeepOriginalWhenRepairReplyCannotBeParsed() throws Exception {
        String streamResponse = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN"><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>你好</h1><script src="script.js"></script></body></html>
                ```
                ```css
                body{
                  margin:0auto;
                  padding:024px;
                }
                ```
                ```javascript
                console.log(1)
                ```
                """;

        long appId = 991002L;
        File dir = productDir(appId);
        try {
            assertNotNull(facadeWith(serviceWith(streamResponse, "抱歉，这次我无法完成修复。"))
                    .generateAndSaveCodeStream("测试页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block());
            assertEquals("body{\n  margin:0auto;\n  padding:024px;\n}", read(dir, "style.css"), "修不了就保留原内容");
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 修复调用抛异常（例如上游超时）时：不抛异常，按原内容落盘 */
    @Test
    void facadeShouldKeepOriginalWhenRepairCallFails() throws Exception {        String streamResponse = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN"><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>你好</h1><script src="script.js"></script></body></html>
                ```
                ```css
                body{
                  margin:0auto;
                  padding:024px;
                }
                ```
                ```javascript
                console.log(1)
                ```
                """;
        // 第一次生成正常返回，修复调用直接报错
        AiCodeGeneratorService failing = AiServices.builder(AiCodeGeneratorService.class)
                .streamingChatModel(new StreamingChatModel() {
                    private final AtomicInteger calls = new AtomicInteger();

                    @Override
                    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        if (calls.getAndIncrement() == 0) {
                            handler.onPartialResponse(streamResponse);
                            handler.onCompleteResponse(ChatResponse.builder()
                                    .aiMessage(AiMessage.from(streamResponse)).build());
                            return;
                        }
                        handler.onError(new IllegalStateException("修复调用失败：上游超时"));
                    }
                })
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        long appId = 991003L;
        File dir = productDir(appId);
        try {
            assertNotNull(facadeWith(failing)
                    .generateAndSaveCodeStream("测试页", CodeGenTypeEnum.MULTI_FILE, appId)
                    .collectList().block());
            assertEquals("body{\n  margin:0auto;\n  padding:024px;\n}", read(dir, "style.css"),
                    "修复调用失败时要按原内容落盘，不能让整轮生成失败");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 记录每次请求上下文的假流式模型（用于验证"多轮流式调用共享对话记忆"）
     */
    private static class RecordingStreamingChatModel implements StreamingChatModel {

        private final List<ChatRequest> requests = new ArrayList<>();

        private final String reply;

        RecordingStreamingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            handler.onPartialResponse(reply);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
        }
    }

    /**
     * 流式接口也要共享对话记忆：第二轮请求必须带上第一轮的用户消息与模型回复
     * <p>
     * 这条链只配置了 {@code StreamingChatModel}，因此记忆的累积与回放必须通过流式方法验证
     * （非流式方法在本应用里根本调不通：{@code chatModel cannot be null}）。
     */
    @Test
    void streamingCallsShouldShareChatMemory() {
        RecordingStreamingChatModel model = new RecordingStreamingChatModel(
                "<!DOCTYPE html><html><head><style>body { margin: 0; }</style></head><body>ok</body></html>");
        AiCodeGeneratorService service = AiServices.builder(AiCodeGeneratorService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        List<String> firstTurnChunks = service.generateHTMLCodeStream("第一轮：做个工具网站").collectList().block();
        assertNotNull(firstTurnChunks);
        assertTrue(String.join("", firstTurnChunks).contains("ok"));
        assertNotNull(service.generateHTMLCodeStream("第二轮：你刚刚做了什么？").collectList().block());

        assertEquals(2, model.requests.size(), "两次流式调用都应打到模型");
        String secondTurnContext = model.requests.get(1).messages().stream()
                .map(Object::toString)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(secondTurnContext.contains("第一轮：做个工具网站"), "第二轮必须带回第一轮的用户消息");
        assertTrue(secondTurnContext.contains("ok"), "第二轮必须带回第一轮的模型回复");
    }

    private static AiCodeGeneratorFacade facadeWith(AiCodeGeneratorService service) {
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", new StubFactory(service));
        return facade;
    }

    private static File productDir(long appId) {
        return new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "multi_file_" + appId);
    }

    private static String read(File dir, String name) throws Exception {
        return Files.readString(new File(dir, name).toPath(), StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            List<File> list = new ArrayList<>(List.of(children));
            for (File child : list) {
                deleteQuietly(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
