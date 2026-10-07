package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private final DeepSeekStreamingChatModelFactory deepSeekFactory = new DeepSeekStreamingChatModelFactory();

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

    private static AiModelProperties.Model deepSeekModel() {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setProvider("deepseek");
        model.setBaseUrl("https://api.deepseek.com/");
        model.setModelName("deepseek-flash");
        model.setApiKey("sk-test");
        return model;
    }

    @Test
    void shouldDeclareProvider() {
        assertEquals(AIProviderEnum.BAILIAN, bailianFactory.provider());
        assertEquals(AIProviderEnum.DEEPSEEK, deepSeekFactory.provider());
    }

    /** 百炼的模型列表地址 = base_url（自动补 /compatible-mode/v1）+ /models */
    @Test
    void bailianModelsUrlShouldContainCompatiblePath() {
        assertEquals("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models",
                bailianFactory.modelsUrl(bailianModel()));
    }

    /** DeepSeek 的 base-url 直接使用，末尾多余斜杠要去掉，避免拼出 //chat/completions */
    @Test
    void deepSeekModelsUrlShouldTrimTrailingSlash() {
        assertEquals("https://api.deepseek.com/models", deepSeekFactory.modelsUrl(deepSeekModel()));
    }

    /** 两个平台都能真的构建出模型对象（构建过程不发请求） */
    @Test
    void shouldBuildStreamingModels() {
        assertNotNull(bailianFactory.create(bailianModel()));
        assertNotNull(deepSeekFactory.create(deepSeekModel()));
    }

    /** enable-thinking 不配置时不能报错（保持平台默认） */
    @Test
    void shouldBuildWithoutEnableThinking() {
        AiModelProperties.Model model = bailianModel();
        model.setEnableThinking(null);
        assertNotNull(bailianFactory.create(model));
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

    /** DeepSeek 缺少 base-url 时报"接口地址" */
    @Test
    void shouldRejectMissingBaseUrlForDeepSeek() {
        AiModelProperties.Model model = deepSeekModel();
        model.setBaseUrl(null);
        BusinessException exception = assertThrows(BusinessException.class, () -> deepSeekFactory.create(model));
        assertTrue(exception.getMessage().contains("接口地址"), exception.getMessage());
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
