package com.example.aicodebackend.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * VueProjectBuilder 的 npm 命令解析与真实构建测试。
 * <p>
 * 回归背景：旧实现固定拼接 "npm.cmd"，在只提供 npm.exe 的 Node 发行版上会直接
 * 报「系统找不到指定的路径」，导致 npm install 与 npm run build 全部失败。
 */
class VueProjectBuilderTest {

    /**
     * 生成项目的构建产物目录名
     */
    private static final String DIST_DIR = "dist";

    /**
     * 端到端真实构建的开关：构建会执行 npm install（联网、耗时），
     * 默认不在 mvn test 中运行；需要验证时设置环境变量 RUN_VUE_BUILD_E2E=true
     */
    private static final String E2E_SWITCH = "RUN_VUE_BUILD_E2E";

    /**
     * 解析出的 npm 命令必须是真实存在的可执行文件，而不是想当然的 npm.cmd
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldResolveExistingNpmExecutableOnWindows() throws Exception {
        List<String> command = resolveNpmCommand();
        assertFalse(command.isEmpty(), "npm 命令不能为空");

        String executable = command.get(0);
        assertTrue(new File(executable).isFile(), "解析出的 npm 可执行文件不存在: " + executable);
        String lowerCase = executable.toLowerCase();
        assertTrue(lowerCase.endsWith("npm.cmd") || lowerCase.endsWith("npm.exe") || lowerCase.endsWith("npm"),
                "解析结果不是 npm 可执行文件: " + executable);
    }

    /**
     * 解析出的 npm 命令必须能够在当前环境下真正执行
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldExecuteResolvedNpmCommand() throws Exception {
        List<String> command = resolveNpmCommand();
        Process process = new ProcessBuilder(command.get(0), "--version")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "npm --version 执行超时");
        assertEquals(0, process.exitValue(), "npm --version 执行失败");
    }

    /**
     * 非 Windows 平台直接使用 npm
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shouldUsePlainNpmOnUnixLikeSystem() throws Exception {
        assertEquals(List.of("npm"), resolveNpmCommand());
    }

    /**
     * 端到端真实构建：用提示词中约定的 Vue3 工程模板，真正执行 npm install + npm run build，
     * 并校验 dist/index.html 是否产出，确保生成的项目可以构建。
     * <p>
     * 需要显式开启：$env:RUN_VUE_BUILD_E2E="true"; mvn test -Dtest=VueProjectBuilderTest
     */
    @Test
    void shouldBuildGeneratedVueProjectEndToEnd() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv(E2E_SWITCH)),
                "未开启端到端构建测试，跳过（设置 " + E2E_SWITCH + "=true 可开启）");
        assumeTrue(isNpmRunnable(), "本机没有可执行的 npm，跳过端到端构建测试");

        Path projectDir = Path.of(System.getProperty("java.io.tmpdir"), "vue-builder-e2e");
        deleteRecursively(projectDir);
        try {
            writeVueProjectFixture(projectDir);

            boolean success = new VueProjectBuilder().buildProject(projectDir.toString());

            assertTrue(success, "Vue 项目构建失败，请检查构建日志");
            Path indexHtml = projectDir.resolve(DIST_DIR).resolve("index.html");
            assertTrue(Files.isRegularFile(indexHtml), "构建完成但没有产出 dist/index.html");
        } finally {
            deleteRecursively(projectDir);
        }
    }

    /**
     * 写入一个最小可构建的 Vue3 工程，与 codegen-vue-project-system-prompt 中的约定保持一致
     * <p>
     * 注意：vite.config.js 必须包含 fileURLToPath 的导入，缺失会直接导致构建失败。
     */
    private void writeVueProjectFixture(Path projectDir) throws IOException {
        // 把 npm 缓存隔离到工程目录内，避免依赖（或污染）用户级 npm 缓存
        writeFile(projectDir.resolve(".npmrc"), "cache=.npm-cache\n");
        writeFile(projectDir.resolve("package.json"), """
                {
                  "name": "vue-builder-e2e",
                  "private": true,
                  "version": "0.0.0",
                  "type": "module",
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build"
                  },
                  "dependencies": {
                    "vue": "^3.3.4",
                    "vue-router": "^4.2.4"
                  },
                  "devDependencies": {
                    "@vitejs/plugin-vue": "^4.2.3",
                    "vite": "^4.4.5"
                  }
                }
                """);
        writeFile(projectDir.resolve("vite.config.js"), """
                import { fileURLToPath, URL } from 'node:url'

                import { defineConfig } from 'vite'
                import vue from '@vitejs/plugin-vue'

                export default defineConfig({
                  base: './',
                  plugins: [vue()],
                  resolve: {
                    alias: {
                      '@': fileURLToPath(new URL('./src', import.meta.url))
                    }
                  }
                })
                """);
        writeFile(projectDir.resolve("index.html"), """
                <!DOCTYPE html>
                <html lang="zh-CN">
                  <head>
                    <meta charset="UTF-8" />
                    <title>端到端构建测试</title>
                  </head>
                  <body>
                    <div id="app"></div>
                    <script type="module" src="/src/main.js"></script>
                  </body>
                </html>
                """);
        writeFile(projectDir.resolve("src/main.js"), """
                import { createApp } from 'vue'
                import App from './App.vue'

                createApp(App).mount('#app')
                """);
        writeFile(projectDir.resolve("src/App.vue"), """
                <template>
                  <div class="app">
                    <h1>{{ title }}</h1>
                  </div>
                </template>

                <script setup>
                import { ref } from 'vue'

                const title = ref('端到端构建测试')
                </script>

                <style scoped>
                .app { padding: 16px; }
                </style>
                """);
    }

    private void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private boolean isNpmRunnable() {
        try {
            List<String> command = resolveNpmCommand();
            Process process = new ProcessBuilder(command.get(0), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 临时目录清理失败（如 Windows 上文件被占用）不影响测试结论
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveNpmCommand() throws Exception {
        Method method = VueProjectBuilder.class.getDeclaredMethod("resolveNpmCommand");
        method.setAccessible(true);
        return (List<String>) method.invoke(new VueProjectBuilder());
    }
}
