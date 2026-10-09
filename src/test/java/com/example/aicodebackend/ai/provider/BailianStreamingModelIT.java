package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阿里云百炼真实调用验证（需要外网 + 真实 API Key）
 * <p>
 * 按仓库惯例用 {@code *IT} 命名，不随 {@code mvn test} 执行。手动验证（环境变量需已配置）：
 * <pre>
 * mvn -o test "-Dtest=BailianStreamingModelIT"
 * </pre>
 * 覆盖三件只有真调用才能确认的事：
 * <ol>
 *     <li>业务空间专属域名 + {@code /compatible-mode/v1} 拼出来的地址确实能通（拼错就是 404/401）；</li>
 *     <li>思考模式：deepseek-v4.1-flash / deepseek-v4-pro / qwen3.8-max / qwen3.8-flash 都会返回
 *     {@code reasoning_content}，而应用在 Vue 工程模式下依赖 onPartialThinking 才有流式输出；</li>
 *     <li>function-calling：带工具的多轮调用能正常收敛（这是"生成 Vue 工程"的核心链路）。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "ALI_AI_API_KEY", matches = ".+")
class BailianStreamingModelIT {

    private static final long TIMEOUT_SECONDS = 180;

    /**
     * 按与 application-dev.yaml 相同的方式组装配置（值取环境变量，避免测试里再抄一份硬编码）
     * <p>
     * 这里刻意与 yaml 保持一致（含 {@code sendThinking=false}），否则验证的是"另一套配置"。
     */
    private static AiModelProperties.Model modelProperties(String modelName) {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setProvider("bailian");
        model.setBaseUrl(System.getenv("ALI_AI_BASE_URL"));
        model.setWorkspaceId(System.getenv("ALI_WORKSPACE_ID"));
        model.setRegion("cn-beijing");
        model.setModelName(modelName);
        model.setApiKey(System.getenv("ALI_AI_API_KEY"));
        model.setMaxTokens(65536);
        model.setEnableThinking(true);
        // 与 application.yaml 保持一致：思考预算必须有，否则推理跑飞时整条链路会一直挂着
        model.setThinkingBudget(2048);
        model.setSendThinking(false);
        return model;
    }

    private static StreamingChatModelRegistry registry() {
        AiModelProperties properties = new AiModelProperties();
        properties.getModels().put("deepseek-v4.1-flash", modelProperties("deepseek-v4.1-flash"));
        properties.getModels().put("deepseek-v4-pro", modelProperties("deepseek-v4-pro"));
        properties.getModels().put("qwen3.8-max", modelProperties("qwen3.8-max"));
        properties.getModels().put("qwen3.8-flash", modelProperties("qwen3.8-flash"));
        return new StreamingChatModelRegistry(properties,
                List.of(new BailianStreamingChatModelFactory(new BailianEndpointResolver())));
    }

    private static StreamingChatModel buildModel(String modelName) {
        return registry().get(AIModelTypeEnum.getEnumByValue(modelName));
    }

    /** 配置能真的构建出模型（地址、Key 齐全） */
    @Test
    void shouldBuildConfiguredModels() {
        StreamingChatModelRegistry registry = registry();
        assertTrue(registry.isAvailable(AIModelTypeEnum.DEEPSEEK_V4_1_FLASH));
        assertTrue(registry.isAvailable(AIModelTypeEnum.DEEPSEEK_V4_PRO));
        assertTrue(registry.isAvailable(AIModelTypeEnum.QWEN_3_8_MAX));
        assertTrue(registry.isAvailable(AIModelTypeEnum.QWEN_3_8_FLASH));
    }

    /**
     * 四个模型都能流式返回正文与思考内容
     *
     * @param modelName 模型名
     *
     * @throws Exception 等待超时
     */
    @ParameterizedTest
    @ValueSource(strings = {"deepseek-v4.1-flash", "deepseek-v4-pro", "qwen3.8-max", "qwen3.8-flash"})
    void shouldAnswerWithThinking(String modelName) throws Exception {
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        buildModel(modelName).chat("只回答两个字：你好", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                text.append(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                thinking.append(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                latch.countDown();
            }
        });

        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "调用超时：" + modelName);
        assertNull(error.get(), () -> "调用失败：" + error.get());
        assertFalse(text.toString().isBlank(), "模型没有返回正文");
        assertFalse(thinking.toString().isBlank(), "思考模式没有返回 reasoning_content");
    }

    /**
     * 工具调用链路（应用生成 Vue 工程时依赖它）
     *
     * @throws Exception 等待超时
     */
    @Test
    void shouldCallTool() throws Exception {
        ClockTool clockTool = new ClockTool();
        ToolProbe probe = AiServices.builder(ToolProbe.class)
                .streamingChatModel(buildModel("qwen3.8-max"))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .tools(clockTool)
                .build();

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        probe.ask("现在几点了？必须调用工具获取，不要自己编。")
                .onPartialResponse(answer::append)
                .onPartialThinking(partial -> thinking.append(partial.text()))
                .onCompleteResponse(response -> latch.countDown())
                .onError(throwable -> {
                    error.set(throwable);
                    latch.countDown();
                })
                .start();

        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "工具调用超时");
        assertNull(error.get(), () -> "工具调用失败：" + error.get());
        assertTrue(clockTool.called.get(), "模型没有调用工具");
        assertFalse(answer.toString().isBlank(), "工具调用后没有给出最终回答");
    }

    /**
     * 可用性巡检能真的拉到平台模型目录：配置里的模型都在，且能看到同类可选项
     * <p>
     * 这条能力就是"模型改名/下架时提前发现"的依据，所以它自己也要被验证，而不是只验证日志能打出来。
     */
    @Test
    void availabilityCheckShouldFetchRealCatalog() {        AiModelProperties properties = new AiModelProperties();
        properties.getModels().put("deepseek-v4.1-flash", modelProperties("deepseek-v4.1-flash"));
        properties.getModels().put("qwen3.8-max", modelProperties("qwen3.8-max"));
        properties.getModelCheck().setEnabled(true);
        BailianStreamingChatModelFactory factory = new BailianStreamingChatModelFactory(new BailianEndpointResolver());
        AiModelAvailabilityChecker checker = new AiModelAvailabilityChecker(properties, List.of(factory));

        Set<String> available = checker.fetchModelIds(
                factory.modelsUrl(modelProperties("qwen3.8-max")), System.getenv("ALI_AI_API_KEY"));

        assertNotNull(available, "模型列表接口不可用");
        assertTrue(available.contains("deepseek-v4.1-flash"),
                "平台目录里没有 deepseek-v4.1-flash：" + available.size() + " 个模型");
        assertTrue(available.contains("qwen3.8-max"), "平台目录里没有 qwen3.8-max：" + available.size() + " 个模型");
    }

    /**
     * 供模型调用的探针工具
     */
    static class ClockTool {

        final AtomicBoolean called = new AtomicBoolean();

        @Tool("获取当前时间")
        String currentTime() {
            called.set(true);
            return "当前时间：2026-01-01 12:00:00";
        }
    }

    /**
     * 工具调用用的小助手（必须是流式返回：本应用只配置了 StreamingChatModel）
     */
    interface ToolProbe {

        @SystemMessage("你可以在需要时调用工具")
        TokenStream ask(@UserMessage String question);
    }
}
