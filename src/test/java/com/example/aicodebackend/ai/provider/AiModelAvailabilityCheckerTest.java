package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.model.enums.AIProviderEnum;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型可用性巡检测试
 * <p>
 * 巡检本身必须"绝对安全"：它跑在后台、访问外网，任何异常都不能影响应用。所以这里既验证
 * 解析与建议逻辑，也验证"接口不可达时不抛异常"。
 */
class AiModelAvailabilityCheckerTest {

    /** 真实接口返回的响应结构（截取自百炼 /compatible-mode/v1/models） */
    private static final String MODELS_RESPONSE = """
            {"object":"list","data":[
              {"id":"qwen3.8-max","object":"model"},
              {"id":"qwen3.8-max-0902","object":"model"},
              {"id":"qwen3.8-flash","object":"model"},
              {"id":"qwen3.7-plus","object":"model"},
              {"id":"qwen3.7-max","object":"model"},
              {"id":"deepseek-v4-pro","object":"model"}
            ],"first_id":"model-id-0","has_more":false}
            """;

    private static StreamingChatModelFactory factory(AIProviderEnum provider, String modelsUrl) {
        return new StreamingChatModelFactory() {
            @Override
            public AIProviderEnum provider() {
                return provider;
            }

            @Override
            public StreamingChatModel create(AiModelProperties.Model properties) {
                throw new UnsupportedOperationException("本用例不构建模型");
            }

            @Override
            public String modelsUrl(AiModelProperties.Model properties) {
                return modelsUrl;
            }
        };
    }

    private static AiModelProperties propertiesWith(Map<String, AiModelProperties.Model> models) {
        AiModelProperties properties = new AiModelProperties();
        properties.getModels().putAll(models);
        properties.getModelCheck().setEnabled(true);
        return properties;
    }

    private static AiModelProperties.Model model(String provider, String modelName, String apiKey) {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setProvider(provider);
        model.setModelName(modelName);
        model.setApiKey(apiKey);
        return model;
    }

    /** 解析 OpenAI 兼容的 /models 响应 */
    @Test
    void shouldParseModelIds() {
        Set<String> ids = AiModelAvailabilityChecker.parseModelIds(MODELS_RESPONSE);
        assertEquals(6, ids.size());
        assertTrue(ids.contains("qwen3.8-max"));
        assertTrue(ids.contains("deepseek-v4-pro"));
    }

    /** 响应不是预期结构时返回 null（由调用方降级成"跳过核对"），不能抛异常 */
    @Test
    void shouldReturnNullForUnexpectedResponse() {
        assertNull(AiModelAvailabilityChecker.parseModelIds("{\"code\":\"123\",\"message\":\"boom\"}"));
        assertNull(AiModelAvailabilityChecker.parseModelIds("not a json"));
    }

    /** 同族建议：qwen3.8-max 不可用时优先给出 qwen3.8-* */
    @Test
    void shouldSuggestSameFamilyModels() {
        Set<String> available = Set.of("qwen3.8-flash", "qwen3.8-max-0902", "qwen3.7-plus", "deepseek-v4-pro");
        List<String> suggestions = AiModelAvailabilityChecker.suggest("qwen3.8-max", available, 5);
        assertEquals(List.of("qwen3.8-flash", "qwen3.8-max-0902"), suggestions);
    }

    /** 同族一个都没有时退一级按主版本给建议（qwen3.8-max → qwen3*） */
    @Test
    void shouldFallbackToMajorVersionSuggestions() {
        Set<String> available = Set.of("qwen3-flash", "qwen3-max", "deepseek-v4-pro");
        List<String> suggestions = AiModelAvailabilityChecker.suggest("qwen3.8-max", available, 5);
        assertEquals(List.of("qwen3-flash", "qwen3-max"), suggestions);
    }

    /** 完全对不上时宁可什么都不建议，也不要给一堆不相干的模型名 */
    @Test
    void shouldNotSuggestUnrelatedModels() {
        assertTrue(AiModelAvailabilityChecker.suggest("qwen3.8-max", Set.of("deepseek-v4-pro"), 5).isEmpty());
        assertTrue(AiModelAvailabilityChecker.suggest("", Set.of("qwen3-max"), 5).isEmpty());
    }

    /** 候选过多时截断并标注总数，避免日志被刷屏 */
    @Test
    void shouldCapSuggestions() {
        Set<String> available = Set.of("qwen3.8-a", "qwen3.8-b", "qwen3.8-c", "qwen3.8-d");
        List<String> suggestions = AiModelAvailabilityChecker.suggest("qwen3.8-max", available, 3);
        assertEquals(4, suggestions.size());
        assertEquals("qwen3.8-a", suggestions.get(0));
        assertTrue(suggestions.get(3).contains("共 4 个"), suggestions.get(3));
    }

    /** 同一个接口地址下的模型合并成一组核对 */
    @Test
    void shouldGroupModelsByEndpoint() {
        AiModelProperties properties = propertiesWith(Map.of(
                "qwen3.8-max", model("bailian", "qwen3.8-max", "sk-1"),
                "qwen3.7-plus", model("bailian", "qwen3.7-plus", "sk-1"),
                "deepseek-flash", model("deepseek", "deepseek-flash", "sk-2")));
        AiModelAvailabilityChecker checker = new AiModelAvailabilityChecker(properties, List.of(
                factory(AIProviderEnum.BAILIAN, "https://ws-a.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models"),
                factory(AIProviderEnum.DEEPSEEK, "https://api.deepseek.com/models")));

        Map<String, AiModelAvailabilityChecker.ProbeGroup> groups = checker.groupByEndpoint();

        assertEquals(2, groups.size(), "两个平台两组，实际=" + groups.keySet());
        AiModelAvailabilityChecker.ProbeGroup bailianGroup = groups.values().stream()
                .filter(group -> group.provider == AIProviderEnum.BAILIAN)
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of("qwen3.8-max", "qwen3.7-plus"), bailianGroup.modelNames);
    }

    /** 没配 Key（环境变量没生效）的模型不参与巡检，避免拿 ${...} 去请求 */
    @Test
    void shouldSkipModelsWithoutApiKey() {
        AiModelProperties properties = propertiesWith(Map.of(
                "qwen3.8-max", model("bailian", "qwen3.8-max", "${ALI_AI_API_KEY}")));
        AiModelAvailabilityChecker checker = new AiModelAvailabilityChecker(properties, List.of(
                factory(AIProviderEnum.BAILIAN, "https://ws-a.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models")));

        assertTrue(checker.groupByEndpoint().isEmpty());
    }

    /** 接口不可达：只记日志，绝不能抛出去（它跑在启动后的后台线程里） */
    @Test
    void checkShouldNeverThrowWhenEndpointIsUnreachable() {
        AiModelProperties properties = propertiesWith(Map.of(
                "qwen3.8-max", model("bailian", "qwen3.8-max", "sk-test")));
        properties.getModelCheck().setTimeout(java.time.Duration.ofMillis(200));
        AiModelAvailabilityChecker checker = new AiModelAvailabilityChecker(properties, List.of(
                factory(AIProviderEnum.BAILIAN, "http://127.0.0.1:1/compatible-mode/v1/models")));

        assertDoesNotThrow(checker::check);
    }
}
