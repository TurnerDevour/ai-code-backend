package com.example.aicodebackend.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vue 构建的部署适配回归测试
 * <p>
 * 回归背景：部署地址是 {@code http://host/{deployKey}/} 这样的子路径，vite 默认 base 为 "/"，
 * 产物会引用 {@code /assets/xxx.js}，部署后必然 404；提示词里虽然要求生成 {@code base: './'}，
 * 但不能保证模型每次都照做，因此在构建前补一层兜底。
 */
class VueProjectBuilderBaseTest {

    @Test
    void shouldInjectRelativeBaseWhenAbsent(@TempDir Path projectDir) throws Exception {
        Path viteConfig = projectDir.resolve("vite.config.js");
        Files.writeString(viteConfig, """
                import { defineConfig } from 'vite'
                import vue from '@vitejs/plugin-vue'

                export default defineConfig({
                  plugins: [vue()]
                })
                """, StandardCharsets.UTF_8);

        invokeEnsureRelativeBase(projectDir.toFile());

        String content = Files.readString(viteConfig, StandardCharsets.UTF_8);
        assertTrue(content.contains("base: './'"), "未注入相对 base，部署到子路径后会 404：\n" + content);
    }

    @Test
    void shouldKeepExplicitBase(@TempDir Path projectDir) throws Exception {
        Path viteConfig = projectDir.resolve("vite.config.js");
        String original = """
                import { defineConfig } from 'vite'
                export default defineConfig({
                  base: '/custom/',
                  plugins: []
                })
                """;
        Files.writeString(viteConfig, original, StandardCharsets.UTF_8);

        invokeEnsureRelativeBase(projectDir.toFile());

        assertEquals(original, Files.readString(viteConfig, StandardCharsets.UTF_8), "已有显式 base 不应被改写");
    }

    @Test
    void shouldNotFailWhenConfigMissing(@TempDir Path projectDir) throws Exception {
        // 没有 vite 配置文件时不应抛异常（是否可构建交给 npm 决定）
        invokeEnsureRelativeBase(projectDir.toFile());
    }

    private void invokeEnsureRelativeBase(java.io.File projectDir) throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod("ensureRelativeBase", java.io.File.class);
        method.setAccessible(true);
        method.invoke(new VueProjectBuilder(), projectDir);
    }
}
