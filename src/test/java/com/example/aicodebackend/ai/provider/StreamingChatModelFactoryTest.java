package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台模型工厂测试
 * <p>
 * 这里不校验"构建出来的模型能不能调通"（那要连外网），只锁两件事：
 * <ul>
 *     <li>接口根地址算得对不对 —— 算错的后果是 404 / 401，靠日志很难一眼看出来；</li>
 *     <li>配置缺项时给的是"说清楚缺什么"的业务异常，而不是 NPE 或半个地址去请求。</li>
 * </ul>
 */
class StreamingChatModelFactoryTest {

    private final BailianEndpointResolver endpointResolver = new BailianEndpointResolver();

    private final BailianStreamingChatModelFactory bailianFactory =
            new BailianStreamingChatModelFactory(endpointResolver);

    private static AiModelProperties.Model bailianModel() {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setProvider("bailian");
        model.setBaseUrl("https://ws-abc.cn-beijing.maas.aliyuncs.com");
        model.setWorkspaceId("ws-abc");
        model.setRegion("cn-beijing");
        model.setModelName("qwen3.8-max");
        model.setApiKey("sk-test");
        model.setMaxTokens(131072);
        model.setEnableThinking(true);
        return model;
    }

    @Test
    void shouldDeclareProvider() {
        assertEquals(AIProviderEnum.BAILIAN, bailianFactory.provider());
    }

    /** 百炼的模型列表地址 = base_url（自动补 /compatible-mode/v1）+ /models */
    @Test
    void bailianModelsUrlShouldContainCompatiblePath() {
        assertEquals("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models",
                bailianFactory.modelsUrl(bailianModel()));
    }

    /** 构建过程不发请求，配置齐全就能拿到模型对象 */
    @Test
    void shouldBuildStreamingModels() {
        assertNotNull(bailianFactory.create(bailianModel()));
    }

    /** enable-thinking 不配置时不能报错（保持平台默认） */
    @Test
    void shouldBuildWithoutEnableThinking() {
        AiModelProperties.Model model = bailianModel();
        model.setEnableThinking(null);
        assertNotNull(bailianFactory.create(model));
    }

    /** reasoning-effort 与 enable-thinking 走同一条 customParameters 通道，配了不能报错 */
    @Test
    void shouldBuildWithReasoningEffort() {
        AiModelProperties.Model model = bailianModel();
        model.setModelName("deepseek-v4.1-flash");
        model.setReasoningEffort("low");
        assertNotNull(bailianFactory.create(model));
    }

    /**
     * 思考预算必须真的进到请求参数里
     * <p>
     * 这是"思考过程死循环"的硬性防线：模型在需求做不到时会长时间自我推敲，只有请求体里带了
     * {@code thinking_budget}，平台侧才会把推理截断（实测同一请求不设预算 10 分钟以上不收敛）。
     */
    @Test
    void shouldPassThinkingBudgetToRequestParameters() {
        AiModelProperties.Model model = bailianModel();
        model.setThinkingBudget(8192);

        OpenAiStreamingChatModel built = (OpenAiStreamingChatModel) bailianFactory.create(model);

        assertEquals(Map.of("enable_thinking", true, "thinking_budget", 8192),
                built.defaultRequestParameters().customParameters());
    }

    /** 没配思考预算时不自己造默认值（各模型支持度不同，只有显式配置才发） */
    @Test
    void shouldNotSendThinkingBudgetWhenAbsent() {
        OpenAiStreamingChatModel built = (OpenAiStreamingChatModel) bailianFactory.create(bailianModel());

        assertFalse(built.defaultRequestParameters().customParameters().containsKey("thinking_budget"));
    }

    /** 缺少 Key 时报"API Key"，而不是拿着 ${ALI_AI_API_KEY} 去请求 */
    @Test
    void shouldRejectMissingApiKey() {
        AiModelProperties.Model model = bailianModel();
        model.setApiKey("${ALI_AI_API_KEY}");
        BusinessException exception = assertThrows(BusinessException.class, () -> bailianFactory.create(model));
        assertTrue(exception.getMessage().contains("API Key"), exception.getMessage());
    }

    /** 缺少模型名时要明确指出 */
    @Test
    void shouldRejectMissingModelName() {
        AiModelProperties.Model model = bailianModel();
        model.setModelName(" ");
        BusinessException exception = assertThrows(BusinessException.class, () -> bailianFactory.create(model));
        assertTrue(exception.getMessage().contains("模型名"), exception.getMessage());
    }

    /** 百炼没有 base-url 也能起来：用 workspace-id + region 拼（环境变量只配了一半时不至于直接失败） */
    @Test
    void bailianShouldFallbackToWorkspaceDomain() {
        AiModelProperties.Model model = bailianModel();
        model.setBaseUrl(null);
        assertEquals("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models",
                bailianFactory.modelsUrl(model));
    }
}
