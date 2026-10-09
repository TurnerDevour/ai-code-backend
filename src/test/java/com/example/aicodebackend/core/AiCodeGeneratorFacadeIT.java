package com.example.aicodebackend.core;

import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 代码生成链路的真机联调（需要外网 + 真实 API Key + MySQL/Redis）
 * <p>
 * 它起的是完整 Spring 上下文，并且真的让模型生成一份代码，因此按仓库惯例用 {@code *IT} 命名，
 * 不随 {@code mvn test} 执行——否则没配 Key 的机器和 CI 上必然是红的。
 * <p>
 * 手动验证（环境变量需已配置，取值见 application-dev.yaml）：
 * <pre>
 * mvn -o test "-Dtest=AiCodeGeneratorFacadeIT"
 * </pre>
 * 这里验证的是"配置齐全时整条链路能跑通"：模型构建 → 流式生成 → 落盘 → 记忆写入；
 * 解析、修复、补齐等细粒度逻辑由同包的单元测试覆盖，不需要真调用。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ALI_AI_API_KEY", matches = ".+")
class AiCodeGeneratorFacadeIT {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("生成一个可交互的博客网站。", CodeGenTypeEnum.MULTI_FILE, 1L);

        List<String> result = codeStream.collectList().block();
        assertNotNull(result);
        String completeContent = String.join("", result);
        assertNotNull(completeContent);
    }

    @Test
    void generateVueProjectCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "简单的任务记录网站，总代码量不超过 200 行",
                CodeGenTypeEnum.VUE_PROJECT, 1L);
        // 阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block();
        // 验证结果
        assertNotNull(result);
        String completeContent = String.join("", result);
        assertNotNull(completeContent);
    }
}
