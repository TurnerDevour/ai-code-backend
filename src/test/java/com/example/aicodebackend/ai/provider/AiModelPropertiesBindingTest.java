package com.example.aicodebackend.ai.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ai.models} 配置绑定测试
 * <p>
 * 锁住一个很容易踩、又完全没有报错的坑：模型名里带 {@code .}（deepseek-v4.1-flash / qwen3.8-max /
 * qwen3.8-flash）时，key 必须写成 {@code "[deepseek-v4.1-flash]"}。Spring 的宽松绑定会把未转义的
 * {@code .} 当层级分隔符，直接写 {@code deepseek-v4.1-flash:} 会被解析成
 * {@code ai.models.deepseek-v4} → {@code 1-flash}，而且因为"未知字段默认忽略"，绑定不会报错，
 * 最后表现为"模型没配置"。
 */
class AiModelPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(AiModelProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    /** 转义写法：带 {@code .} 的模型 key 整份配置都能绑上（含平台专属字段），不带 {@code .} 的直接写 */
    @Test
    void shouldBindDottedModelKeyWithBrackets() {
        runner.withPropertyValues(
                "ai.models.deepseek-v4-pro.provider=bailian",
                "ai.models.deepseek-v4-pro.model-name=deepseek-v4-pro",
                "ai.models.deepseek-v4-pro.timeout=600s",
                "ai.models.[deepseek-v4.1-flash].provider=bailian",
                "ai.models.[deepseek-v4.1-flash].model-name=deepseek-v4.1-flash",
                "ai.models.[deepseek-v4.1-flash].api-key=sk-test",
                "ai.models.[deepseek-v4.1-flash].send-thinking=false",
                "ai.models.[deepseek-v4.1-flash].enable-thinking=true",
                "ai.models.[qwen3.8-max].provider=bailian",
                "ai.models.[qwen3.8-max].model-name=qwen3.8-max",
                "ai.models.[qwen3.8-max].api-key=sk-test",
                "ai.models.[qwen3.8-max].base-url=https://ws-abc.cn-beijing.maas.aliyuncs.com",
                "ai.models.[qwen3.8-max].workspace-id=ws-abc",
                "ai.models.[qwen3.8-max].region=cn-beijing",
                "ai.models.[qwen3.8-max].max-tokens=131072",
                "ai.models.[qwen3.8-max].enable-thinking=true",
                "ai.models.[qwen3.8-max].thinking-budget=8192"
        ).run(context -> {
            AiModelProperties properties = context.getBean(AiModelProperties.class);
            assertEquals(Set.of("deepseek-v4-pro", "deepseek-v4.1-flash", "qwen3.8-max"),
                    new LinkedHashSet<>(properties.getModels().keySet()));
            AiModelProperties.Model flash = properties.getModels().get("deepseek-v4.1-flash");
            assertEquals("bailian", flash.getProvider());
            assertEquals("deepseek-v4.1-flash", flash.getModelName());
            assertFalse(flash.isSendThinking());
            assertTrue(flash.getEnableThinking());
            AiModelProperties.Model qwen = properties.getModels().get("qwen3.8-max");
            assertEquals("bailian", qwen.getProvider());
            assertEquals("qwen3.8-max", qwen.getModelName());
            assertEquals("ws-abc", qwen.getWorkspaceId());
            assertEquals("cn-beijing", qwen.getRegion());
            assertEquals(131072, qwen.getMaxTokens());
            assertTrue(qwen.getEnableThinking());
            assertEquals(8192, qwen.getThinkingBudget());
            assertEquals(600, properties.getModels().get("deepseek-v4-pro").getTimeout().toSeconds());
        });
    }

    /** 不转义时绑定不上：这里把"实际会怎样"固定下来，避免以后有人把方括号"优化"掉 */
    @Test
    void shouldNotBindDottedKeyWithoutBrackets() {
        Set<String> boundKeys = new LinkedHashSet<>();
        try {
            runner.withPropertyValues(
                    "ai.models.qwen3.8-max.model-name=qwen3.8-max",
                    "ai.models.qwen3.8-max.api-key=sk-test",
                    "ai.models.deepseek-v4.1-flash.model-name=deepseek-v4.1-flash",
                    "ai.models.deepseek-v4.1-flash.api-key=sk-test"
            ).run(context -> boundKeys.addAll(context.getBean(AiModelProperties.class).getModels().keySet()));
        } catch (Exception e) {
            // 直接绑定失败也算"这种写法拿不到配置"
            boundKeys.add("绑定失败:" + e.getClass().getSimpleName());
        }
        assertFalse(boundKeys.contains("qwen3.8-max"),
                () -> "未转义的 . 会被当成层级分隔符，key 必须写成 \"[qwen3.8-max]\"，实际绑定到：" + boundKeys);
        assertFalse(boundKeys.contains("deepseek-v4.1-flash"),
                () -> "未转义的 . 会被当成层级分隔符，key 必须写成 \"[deepseek-v4.1-flash]\"，实际绑定到：" + boundKeys);
    }

    /** 巡检开关默认关闭：多一个外网请求不应该被隐式打开 */
    @Test
    void modelCheckShouldBeDisabledByDefault() {
        runner.run(context -> assertFalse(context.getBean(AiModelProperties.class).getModelCheck().isEnabled()));
    }

    /** 巡检开关可以显式打开 */
    @Test
    void modelCheckShouldBeConfigurable() {
        runner.withPropertyValues("ai.model-check.enabled=true", "ai.model-check.timeout=3s")
                .run(context -> {
                    AiModelProperties.ModelCheck modelCheck = context.getBean(AiModelProperties.class).getModelCheck();
                    assertTrue(modelCheck.isEnabled());
                    assertEquals(3, modelCheck.getTimeout().toSeconds());
                });
    }

    /** provider 写成未知值时不该在这里失败（由注册表报告并只影响该模型） */
    @Test
    void unknownProviderShouldBindWithoutFailure() {
        runner.withPropertyValues(
                "ai.models.[qwen3.8-max].provider=typo",
                "ai.models.[qwen3.8-max].model-name=qwen3.8-max"
        ).run(context -> {
            AiModelProperties properties = context.getBean(AiModelProperties.class);
            assertEquals("typo", properties.getModels().get("qwen3.8-max").getProvider());
        });
    }

    /** 缺少必填项时由工厂报错，绑定阶段不能抛（否则一个模型配错会让应用起不来） */
    @Test
    void bindingShouldNotValidateRequiredFields() {
        runner.withPropertyValues("ai.models.[qwen3.8-max].provider=bailian")
                .run(context -> {
                    AiModelProperties properties = context.getBean(AiModelProperties.class);
                    assertTrue(properties.getModels().containsKey("qwen3.8-max"));
                    assertFalse(AbstractStreamingChatModelFactory.isUnresolved(
                            properties.getModels().get("qwen3.8-max").getProvider()));
                });
    }

    /** 未解析的环境变量占位符会被识别成"缺失"，而不是当成一个合法的 Key */
    @Test
    void shouldDetectUnresolvedPlaceholder() {
        assertTrue(AbstractStreamingChatModelFactory.isUnresolved("${ALI_AI_API_KEY}"));
        assertTrue(AbstractStreamingChatModelFactory.isUnresolved("   "));
        assertTrue(AbstractStreamingChatModelFactory.isUnresolved(null));
        assertFalse(AbstractStreamingChatModelFactory.isUnresolved("sk-real-key"));
        assertFalse(AbstractStreamingChatModelFactory.isUnresolved("${NOT_CLOSED"));
    }

    /**
     * {@code ai.model-defaults} 里的共用配置会补到每个模型上，模型自己写过的值优先
     * <p>
     * 这正是"base-url / api-key 不必在每个模型里抄一遍"的实现：只填空、不覆盖。
     */
    @Test
    void modelDefaultsShouldFillMissingFieldsOnly() {
        runner.withPropertyValues(
                // 共用配置：业务空间凭据、统一开关
                "ai.model-defaults.base-url=https://ws-shared.cn-beijing.maas.aliyuncs.com",
                "ai.model-defaults.workspace-id=ws-shared",
                "ai.model-defaults.api-key=sk-shared",
                "ai.model-defaults.timeout=600s",
                "ai.model-defaults.thinking-budget=8192",
                "ai.model-defaults.send-thinking=false",
                "ai.model-defaults.log-requests=false",
                // 模型自己的差异：一个只写模型名，另一个覆盖了 api-key 与超时
                "ai.models.[qwen3.8-max].model-name=qwen3.8-max",
                "ai.models.[qwen3.8-flash].model-name=qwen3.8-flash",
                "ai.models.[qwen3.8-flash].api-key=sk-own",
                "ai.models.[qwen3.8-flash].timeout=30s",
                "ai.models.[qwen3.8-flash].thinking-budget=2048"
        ).run(context -> {
            AiModelProperties properties = context.getBean(AiModelProperties.class);

            AiModelProperties.Model inherited = properties.getModels().get("qwen3.8-max");
            assertEquals("https://ws-shared.cn-beijing.maas.aliyuncs.com", inherited.getBaseUrl(), "共用 base-url 应被继承");
            assertEquals("ws-shared", inherited.getWorkspaceId());
            assertEquals("sk-shared", inherited.getApiKey());
            assertEquals(8192, inherited.getThinkingBudget());
            assertEquals(600, inherited.getTimeout().toSeconds());
            assertFalse(inherited.isSendThinking(), "默认值要能覆盖开关的内置默认（true）");
            assertFalse(inherited.isLogRequests());

            AiModelProperties.Model overridden = properties.getModels().get("qwen3.8-flash");
            assertEquals("sk-own", overridden.getApiKey(), "模型自己写的值必须优先于默认值");
            assertEquals(30, overridden.getTimeout().toSeconds());
            assertEquals(2048, overridden.getThinkingBudget());
            assertEquals("https://ws-shared.cn-beijing.maas.aliyuncs.com", overridden.getBaseUrl(), "没写的项仍要继承");
        });
    }

    /** model-name 不参与继承：它是模型的身份，默认值里写了也不能顶替 */
    @Test
    void modelNameShouldNeverBeInherited() {
        runner.withPropertyValues(
                "ai.model-defaults.model-name=some-default-name",
                "ai.model-defaults.api-key=sk-shared",
                "ai.models.[qwen3.8-max].model-name=qwen3.8-max"
        ).run(context -> {
            AiModelProperties properties = context.getBean(AiModelProperties.class);
            assertEquals("qwen3.8-max", properties.getModels().get("qwen3.8-max").getModelName());
        });
    }

    /** 没配默认值时的兜底：平台/地域/温度/开关都有内置默认，不会因为"只写了模型名"而变成空值 */
    @Test
    void builtInDefaultsShouldSurviveWhenNothingConfigured() {
        runner.withPropertyValues("ai.models.[qwen3.8-max].model-name=qwen3.8-max").run(context -> {
            AiModelProperties.Model model = context.getBean(AiModelProperties.class).getModels().get("qwen3.8-max");
            assertEquals("bailian", model.getProvider());
            assertEquals("cn-beijing", model.getRegion());
            assertEquals(0.3, model.getTemperature());
            assertTrue(model.isReturnThinking());
            assertTrue(model.isSendThinking(), "未配置时保持 LangChain4j 的默认（回传 thinking）");
            assertTrue(model.isLogRequests());
            assertTrue(model.isLogResponses());
        });
    }

    /** yaml 里写成 {@code key:}（值为 null）的模型：语义是"全部走默认值"，取配置时不能 NPE */
    @Test
    void emptyModelEntryShouldFallBackToDefaultsEntirely() {
        AiModelProperties properties = new AiModelProperties();
        properties.getModelDefaults().setBaseUrl("https://ws-shared.cn-beijing.maas.aliyuncs.com");
        properties.getModelDefaults().setApiKey("sk-shared");
        properties.getModels().put("deepseek-v4-pro", null);

        AiModelProperties.Model model = properties.getModels().get("deepseek-v4-pro");

        assertNotNull(model, "值为 null 的模型条目应被换成空对象，而不是继续为 null");
        assertEquals("sk-shared", model.getApiKey());
        assertEquals("https://ws-shared.cn-beijing.maas.aliyuncs.com", model.getBaseUrl());
        // 重复取仍然是同一个对象（合并是幂等的，不会每次生成新配置）
        assertSame(model, properties.getModels().get("deepseek-v4-pro"));
    }
}
