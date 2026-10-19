package com.example.aicodebackend.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署构建暂存目录的回归测试
 * 回归背景（线上事故）：{@code createBuildStagingDir} 用 {@code FileUtil.copyContent} 把源码目录整份复制到……
 */
class AppServiceImplBuildStagingTest {

    private final AppServiceImpl appService = new AppServiceImpl();

    @Test
    @DisplayName("暂存目录只复制源码：node_modules 与 dist 不带过去（否则 .bin/vite 会被实体化导致构建失败）")
    void shouldSkipNodeModulesAndDist(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("vue_project_1");

        Files.createDirectories(source.resolve("src/components"));
        Files.writeString(source.resolve("package.json"), "{\"name\":\"demo\"}");
        Files.writeString(source.resolve("src/main.js"), "console.log(1)");
        Files.writeString(source.resolve("src/components/Hello.vue"), "<template><div/></template>");

        Files.createDirectories(source.resolve("node_modules/.bin"));
        Files.writeString(source.resolve("node_modules/.bin/vite"), "import '../dist/node/cli.js'");
        Files.createDirectories(source.resolve("node_modules/vite/dist/node"));
        Files.writeString(source.resolve("node_modules/vite/dist/node/cli.js"), "// vite cli");

        Files.createDirectories(source.resolve("dist"));
        Files.writeString(source.resolve("dist/index.html"), "<html></html>");

        Path staging = (Path) ReflectionTestUtils.invokeMethod(
                appService, "createBuildStagingDir", source.toFile(), 1L);

        assertTrue(Files.exists(staging.resolve("package.json")), "源码文件必须被复制");
        assertTrue(Files.exists(staging.resolve("src/main.js")), "子目录里的源码必须被复制");
        assertTrue(Files.exists(staging.resolve("src/components/Hello.vue")), "更深层的源码必须被复制");
        assertFalse(Files.exists(staging.resolve("node_modules")), "node_modules 不能被复制：正是它导致 .bin/vite 被实体化");
        assertFalse(Files.exists(staging.resolve("node_modules/.bin/vite")), ".bin/vite 不能出现在暂存目录里");
        assertFalse(Files.exists(staging.resolve("dist")), "上一次的 dist 不能被复制");
    }
}
