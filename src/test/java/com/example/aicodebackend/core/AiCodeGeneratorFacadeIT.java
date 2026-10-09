package com.example.aicodebackend.core;

import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p>
 * 另外它锁住一条只有真调用才能确认的行为：HTML / 多文件模式也要下发思考过程
 * （{@code type=ai_thinking}）。这两个模式曾经因为用 {@code Flux<String>} 接模型输出，
 * 思考内容被 langchain4j 的 Reactor 适配器直接丢掉，前端「AI 思考过程」面板永远是空的。
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

        int thinkingChars = result.stream()
                .filter(message -> "ai_thinking".equals(JSONUtil.parseObj(message).getStr("type")))
                .mapToInt(message -> JSONUtil.parseObj(message).getStr("data").length())
                .sum();
        int responseChars = result.stream()
                .filter(message -> "ai_response".equals(JSONUtil.parseObj(message).getStr("type")))
                .mapToInt(message -> JSONUtil.parseObj(message).getStr("data").length())
                .sum();
        assertTrue(responseChars > 0, "多文件模式没有下发正文增量");
        assertTrue(thinkingChars > 0,
                "多文件模式没有下发思考过程（ai_thinking）：检查模型是否开启了思考模式，"
                        + "以及整条链路是否还在用会丢弃 reasoning_content 的 Flux<String>");
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
        assertFalse(result.isEmpty(), "Vue 工程模式没有产生任何消息");
    }
}
