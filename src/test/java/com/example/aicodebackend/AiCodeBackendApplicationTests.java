package com.example.aicodebackend;

import cn.hutool.crypto.digest.DigestUtil;
import com.example.aicodebackend.ai.provider.AiModelProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@SpringBootTest
class AiCodeBackendApplicationTests {

    @Resource
    private AiModelProperties aiModelProperties;

    @Test
    void testMd5() {
        String password = "12345678";
        String salt = "ai_code";
        String encryptedPassword = DigestUtil.md5Hex(salt + password);
        System.out.println("加密后的密码：" + encryptedPassword);
    }

    /**
     * 随应用发布的配置必须真的靠 {@code ai.model-defaults} 把共用项补齐到每个模型上
     * <p>
     * 守着两件事：
     * <ol>
     *     <li>模型条目里只写 {@code model-name} / {@code max-tokens} 也能拿到 base-url、思考预算等共用配置；
     *     （凭据的具体取值依赖环境变量，这里只断言"继承到了"，不断言值）</li>
     *     <li>yaml 里把 {@code model-defaults} 写错名字时不会报错，只会让每个模型都缺 Key / 思考预算——
     *     这条断言就是让那种静默失效变成一条红测试。</li>
     * </ol>
     */
    @Test
    void shippedModelDefaultsShouldApplyToEveryModel() {
        Map<String, AiModelProperties.Model> models = aiModelProperties.getModels();

        assertEquals(4, models.size(), "配置里的模型数量变了，请同步这条断言：" + models.keySet());
        models.forEach((key, model) -> {
            assertNotNull(model.getModelName(), key + " 缺少 model-name");
            assertNotNull(model.getBaseUrl(), key + " 没有继承到 base-url");
            assertNotNull(model.getWorkspaceId(), key + " 没有继承到 workspace-id");
            assertNotNull(model.getApiKey(), key + " 没有继承到 api-key");
            assertNotNull(model.getThinkingBudget(), key + " 没有继承到 thinking-budget");
            assertFalse(model.isSendThinking(), key + " 没有继承到 send-thinking=false");
            assertTrue(model.getEnableThinking(), key + " 没有继承到 enable-thinking=true");
            assertEquals(600, model.getTimeout().toSeconds(), key + " 没有继承到 timeout=600s");
            assertEquals(0.3, model.getTemperature(), key + " 没有继承到 temperature=0.3");
        });
    }
}
