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
// 单元测试不依赖外部 broker：禁用 RabbitMQ 监听器自动启动，否则本地没有 RabbitMQ 时
// 监听容器会在上下文刷新阶段一直重连、把整个测试挂住（部署队列的消费逻辑由 Mock 单测覆盖）
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
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
     * 守着两件事……
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
