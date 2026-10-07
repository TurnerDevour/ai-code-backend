package com.example.aicodebackend.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 构建临时目录必须落在工程旁边，而不是系统临时目录
     * <p>
     * 回归背景：esbuild 对超过 1 MiB 的输入会写临时文件到 {@code os.tmpdir()} 再删除，
     * 部分 Windows 环境下这个删除动作会以 {@code Access is denied} 失败（实测：带 three.js 的工程），
     * 导致 vite build 退出码 1、dist 不产出、前端预览区一片空白。
     */
    @Test
    void shouldCreateBuildTempDirBesideProject(@TempDir Path root) throws Exception {
        Path projectDir = Files.createDirectory(root.resolve("vue_project_1"));

        File tempDir = invokeCreateBuildTempDir(projectDir.toFile());

        assertTrue(tempDir.isDirectory(), "构建临时目录未创建: " + tempDir);
        assertEquals(root.toFile().getAbsoluteFile(), tempDir.getAbsoluteFile().getParentFile(),
                "构建临时目录应放在工程同级目录（生成根目录内），而不是系统临时目录");
        assertTrue(tempDir.getName().startsWith(".build-tmp-"), "构建临时目录命名不符合约定: " + tempDir.getName());
    }

    /**
     * 构建子进程必须拿到指向该临时目录的 TEMP/TMP，否则修复不会生效
     */
    @Test
    void shouldPassBuildTempDirToProcessEnvironment(@TempDir Path root) throws Exception {
        Path projectDir = Files.createDirectory(root.resolve("vue_project_2"));
        File tempDir = invokeCreateBuildTempDir(projectDir.toFile());

        ProcessBuilder processBuilder = invokeCreateProcessBuilder(projectDir.toFile(), List.of("npm", "run", "build"), tempDir);

        String expected = tempDir.getAbsolutePath();
        assertEquals(expected, processBuilder.environment().get("TEMP"), "未把 TEMP 指向构建临时目录");
        assertEquals(expected, processBuilder.environment().get("TMP"), "未把 TMP 指向构建临时目录");
        assertEquals(expected, processBuilder.environment().get("TMPDIR"), "未把 TMPDIR 指向构建临时目录");
    }

    /**
     * 失败原因要能从构建输出里摘出来，并且剥掉终端颜色控制符（否则前端显示乱码）
     */
    @Test
    void shouldSummarizeBuildFailureWithoutAnsiEscape() throws Exception {
        Deque<String> outputTail = new ArrayDeque<>();
        outputTail.add("\u001B[36mvite v4.5.14 building for production...\u001B[39m");
        outputTail.add("✓ 38 modules transformed.");
        outputTail.add("\u001B[31m[vite:esbuild-transpile] remove C:\\Temp\\esbuild-abc: Access is denied.\u001B[39m");
        outputTail.add("error during build:");
        outputTail.add("");

        String summary = invokeSummarizeFailure(outputTail);

        assertTrue(summary.contains("Access is denied"), "失败原因未包含关键错误: " + summary);
        assertFalse(summary.contains("\u001B"), "失败原因里残留了终端颜色控制符: " + summary);
    }

    private File invokeCreateBuildTempDir(File projectDir) throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod("createBuildTempDir", File.class);
        method.setAccessible(true);
        return (File) method.invoke(new VueProjectBuilder(), projectDir);
    }

    private ProcessBuilder invokeCreateProcessBuilder(File workingDir, List<String> command, File buildTempDir) throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod(
                "createProcessBuilder", File.class, List.class, File.class);
        method.setAccessible(true);
        return (ProcessBuilder) method.invoke(new VueProjectBuilder(), workingDir, command, buildTempDir);
    }

    @SuppressWarnings("unchecked")
    private String invokeSummarizeFailure(Deque<String> outputTail) throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod("summarizeFailure", Deque.class);
        method.setAccessible(true);
        return (String) method.invoke(new VueProjectBuilder(), outputTail);
    }

    private void invokeEnsureRelativeBase(java.io.File projectDir) throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod("ensureRelativeBase", java.io.File.class);
        method.setAccessible(true);
        method.invoke(new VueProjectBuilder(), projectDir);
    }
}
