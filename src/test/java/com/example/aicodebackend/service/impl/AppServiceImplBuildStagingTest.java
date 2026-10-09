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
 * <p>
 * 回归背景（线上事故）：{@code createBuildStagingDir} 用 {@code FileUtil.copyContent} 把源码目录整份复制到
 * 暂存目录，而 Linux 上 npm 会把 {@code node_modules/.bin/*} 做成<b>符号链接</b>，
 * {@code Files.copy} 默认跟随符号链接，于是 {@code .bin/vite} 被"实体化"成 {@code vite/bin/vite.js} 的副本；
 * 它里面的 {@code import '../dist/node/cli.js'} 就被解析成 {@code node_modules/dist/node/cli.js}（不存在），
 * 部署构建直接失败：
 * <pre>
 * Error [ERR_MODULE_NOT_FOUND]: Cannot find module
 *   '/app/temp/code_output/.build-staging-xxx/node_modules/dist/node/cli.js'
 *   imported from '/app/temp/code_output/.build-staging-xxx/node_modules/.bin/vite'
 * </pre>
 * 而随后的 {@code npm install} 在依赖树没变化时不会重建 bin 链接，修不回来。
 * Windows 上 npm 不产生这类符号链接，所以本地不复现，只有线上（Linux）会炸。
 * <p>
 * 修复：复制时跳过工程根目录下的 {@code node_modules} 与 {@code dist}（这两个目录由本次构建自己重新生成）。
 * 本测试用"node_modules 里存在文件"来覆盖该行为，不依赖符号链接，因此在 Windows / Linux 上都能跑。
 */
class AppServiceImplBuildStagingTest {

    private final AppServiceImpl appService = new AppServiceImpl();

    @Test
    @DisplayName("暂存目录只复制源码：node_modules 与 dist 不带过去（否则 .bin/vite 会被实体化导致构建失败）")
    void shouldSkipNodeModulesAndDist(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("vue_project_1");
        // 1. 真正的源码：必须原样复制（含子目录）
        Files.createDirectories(source.resolve("src/components"));
        Files.writeString(source.resolve("package.json"), "{\"name\":\"demo\"}");
        Files.writeString(source.resolve("src/main.js"), "console.log(1)");
        Files.writeString(source.resolve("src/components/Hello.vue"), "<template><div/></template>");
        // 2. 上一次预览构建留下的 node_modules（线上就是在这一步把 .bin/vite 变成真实文件的）
        Files.createDirectories(source.resolve("node_modules/.bin"));
        Files.writeString(source.resolve("node_modules/.bin/vite"), "import '../dist/node/cli.js'");
        Files.createDirectories(source.resolve("node_modules/vite/dist/node"));
        Files.writeString(source.resolve("node_modules/vite/dist/node/cli.js"), "// vite cli");
        // 3. 上一次构建留下的 dist
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
