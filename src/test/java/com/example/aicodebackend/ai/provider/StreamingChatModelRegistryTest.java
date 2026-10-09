package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型注册表测试
 * <p>
 * 重点是"配置错一个不影响其它"这条容错线：模型注册发生在应用启动期，
 * 一个平台的 key 没配好不应该让整个服务起不来，但被选中时必须给出能照着排查的报错。
 */
class StreamingChatModelRegistryTest {

    /** 只用于占位，本用例不调用模型 */
    private static final StreamingChatModel DUMMY = new StreamingChatModel() {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            throw new UnsupportedOperationException("测试用占位模型");
        }
    };

    /** 按平台返回占位模型，可让指定模型名构建失败 */
    private static final class FakeFactory implements StreamingChatModelFactory {

        private final AIProviderEnum provider;

        private final Set<String> failingModelNames;

        private FakeFactory(AIProviderEnum provider, Set<String> failingModelNames) {
            this.provider = provider;
            this.failingModelNames = failingModelNames;
        }

        @Override
        public AIProviderEnum provider() {
            return provider;
        }

        @Override
        public StreamingChatModel create(AiModelProperties.Model properties) {
            if (failingModelNames.contains(properties.getModelName())) {
                throw new BusinessException(com.example.aicodebackend.exception.ErrorCode.SYSTEM_ERROR,
                        "缺少 API Key：请配置环境变量");
            }
            return DUMMY;
        }
    }

    private static List<StreamingChatModelFactory> factories(Set<String> failingModelNames) {
        return List.of(new FakeFactory(AIProviderEnum.BAILIAN, failingModelNames));
    }

    private static AiModelProperties.Model model(String provider, String modelName) {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setProvider(provider);
        model.setModelName(modelName);
        return model;
    }

    private static AiModelProperties properties(Map<String, AiModelProperties.Model> models) {
        AiModelProperties properties = new AiModelProperties();
        properties.getModels().putAll(models);
        return properties;
    }

    /** 配置里的模型按 AIModelTypeEnum.value 建档，可以直接按枚举取到 */
    @Test
    void shouldRegisterConfiguredModels() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of(
                "deepseek-v4.1-flash", model("bailian", "deepseek-v4.1-flash"),
                "qwen3.8-max", model("bailian", "qwen3.8-max"),
                "qwen3.7-plus", model("bailian", "qwen3.7-plus"))), factories(Set.of()));

        assertSame(DUMMY, registry.get(AIModelTypeEnum.DEEPSEEK_V4_1_FLASH));
        assertSame(DUMMY, registry.get(AIModelTypeEnum.QWEN_3_8_MAX));
        assertSame(DUMMY, registry.get(AIModelTypeEnum.QWEN_3_7_PLUS));
        assertTrue(registry.isAvailable(AIModelTypeEnum.QWEN_3_7_PLUS));
    }

    /** 某一个模型构建失败只影响它自己：出错信息要保留原因，其它模型照常可用 */
    @Test
    void shouldIsolateSingleModelFailure() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of(
                "deepseek-v4.1-flash", model("bailian", "deepseek-v4.1-flash"),
                "qwen3.8-max", model("bailian", "qwen3.8-max"))), factories(Set.of("qwen3.8-max")));

        assertSame(DUMMY, registry.get(AIModelTypeEnum.DEEPSEEK_V4_1_FLASH), "另一个模型必须不受影响");
        assertFalse(registry.isAvailable(AIModelTypeEnum.QWEN_3_8_MAX));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.get(AIModelTypeEnum.QWEN_3_8_MAX));
        assertTrue(exception.getMessage().contains("qwen3.8-max"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ai.models"), exception.getMessage());
    }

    /** 完全没配置的模型类型：报错要指出该配哪个 key，而不是抛 NPE */
    @Test
    void shouldThrowForUnconfiguredModelType() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of()),
                factories(Set.of()));

        assertTrue(registry.availableModelTypes().isEmpty());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.get(AIModelTypeEnum.QWEN_3_7_PLUS));
        assertTrue(exception.getMessage().contains("ai.models.qwen3.7-plus"), exception.getMessage());
    }

    /** 未注册的模型标识（枚举里没有）只记警告，不能影响启动 */
    @Test
    void shouldIgnoreUnknownModelKey() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of(
                "gpt-5", model("bailian", "gpt-5"),
                "deepseek-v4.1-flash", model("bailian", "deepseek-v4.1-flash"))), factories(Set.of()));

        assertEquals(1, registry.availableModelTypes().size());
        assertTrue(registry.isAvailable(AIModelTypeEnum.DEEPSEEK_V4_1_FLASH));
    }

    /** 平台名写错：该模型不可用，报错里带上枚举值方便对照 */
    @Test
    void shouldRejectUnknownProvider() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of(
                "qwen3.8-max", model("aliyun", "qwen3.8-max"))), factories(Set.of()));

        assertFalse(registry.isAvailable(AIModelTypeEnum.QWEN_3_8_MAX));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> registry.get(AIModelTypeEnum.QWEN_3_8_MAX));
        assertTrue(exception.getMessage().contains("ai.models.qwen3.8-max"), exception.getMessage());
    }

    /** 平台枚举有值但没有对应工厂实现时也必须降级成"不可用"，而不是 NPE */
    @Test
    void shouldRejectProviderWithoutFactory() {
        StreamingChatModelRegistry registry = new StreamingChatModelRegistry(properties(Map.of(
                "qwen3.8-max", model("bailian", "qwen3.8-max"))),
                List.of());

        assertFalse(registry.isAvailable(AIModelTypeEnum.QWEN_3_8_MAX));
    }
}
