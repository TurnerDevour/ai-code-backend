package com.example.aicodebackend.ai;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class AiCodeGeneratorServiceTest {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Test
    void generateHTMLCode() {
        String prompt = "写一个计算器的HTML页面";
        String codeResult = aiCodeGeneratorService.generateHTMLCode(prompt);
        log.info("生成的HTML代码:\n{}", codeResult);
    }

    @Test
    void generateMultipleFileCode() {
        String prompt = "写一个计算器小工具。";
        String codeResult = aiCodeGeneratorService.generateMultipleFileCode(prompt);
        log.info("生成的多文件代码:\n{}", codeResult);
    }
}
