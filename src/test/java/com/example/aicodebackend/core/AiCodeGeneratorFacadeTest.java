package com.example.aicodebackend.core;

import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCode() {
        File generated = aiCodeGeneratorFacade.generateAndSaveCode("生成一个博客网站", CodeGenTypeEnum.MULTI_FILE);
        assertNotNull(generated);
    }
}
