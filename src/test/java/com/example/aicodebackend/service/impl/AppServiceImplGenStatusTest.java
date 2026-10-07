package com.example.aicodebackend.service.impl;

import cn.hutool.core.io.FileUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.vo.GenerationStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "没有生成任务时"的状态查询回归测试
 * <p>
 * 背景（实测线上表现）：生成任务注册表是进程内的，后端一重启任务就没了。此前这种场景一律回
 * {@code status=none, buildStatus=idle}，前端据此认为"构建还没做完"，于是每 1.5 秒轮询一次状态接口
 * 直到上限——后端日志里 {@code SELECT user} / {@code SELECT app} 一直在刷，而预览区一直空白。
 * 修复后：Vue 工程只要产物（dist/index.html）已经存在，就直接回 {@code buildStatus=finished}，
 * 前端立刻刷新预览、不再轮询。
 */
class AppServiceImplGenStatusTest {

    /** 专用测试应用 id，避免与真实数据/其它用例互相影响 */
    private static final long VUE_APP_WITH_DIST = 990000000000002001L;
    private static final long VUE_APP_WITHOUT_DIST = 990000000000002002L;
    private static final long HTML_APP_WITHOUT_DIST = 990000000000002003L;

    private final AppServiceImpl appService = new AppServiceImpl();

    @AfterEach
    void tearDown() {
        FileUtil.del(sourceDir(CodeGenTypeEnum.VUE_PROJECT.getValue(), VUE_APP_WITH_DIST).toFile());
        FileUtil.del(sourceDir(CodeGenTypeEnum.VUE_PROJECT.getValue(), VUE_APP_WITHOUT_DIST).toFile());
        FileUtil.del(sourceDir(CodeGenTypeEnum.MULTI_FILE.getValue(), HTML_APP_WITHOUT_DIST).toFile());
    }

    private static Path sourceDir(String codeGenType, long appId) {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenType + "_" + appId);
    }

    private static Path vueSourceDir(long appId) {
        return sourceDir(CodeGenTypeEnum.VUE_PROJECT.getValue(), appId);
    }

    private static App vueApp(long appId) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        return app;
    }

    /** Vue 工程：产物已就绪 → 直接告诉前端 finished，前端不需要再等（也不该再轮询） */
    @Test
    @DisplayName("无任务但 dist 已产出时，构建状态应为 finished")
    void vueAppWithDistShouldReportFinished() throws Exception {
        Path dist = Files.createDirectories(vueSourceDir(VUE_APP_WITH_DIST).resolve("dist"));
        Files.writeString(dist.resolve("index.html"), "<!doctype html><html></html>");

        GenerationStatusVO vo = invokeNoTaskStatus(VUE_APP_WITH_DIST, vueApp(VUE_APP_WITH_DIST));

        assertEquals("none", vo.getStatus(), "没有任务时生成状态仍是 none");
        assertEquals("finished", vo.getBuildStatus(), "产物已存在就必须报告构建完成，否则前端会一直轮询");
        assertEquals(Boolean.FALSE, vo.getRunning());
    }

    /** Vue 工程：从未成功构建 → 维持 idle（前端按既有上限轮询后自行兜底刷新） */
    @Test
    @DisplayName("无任务且没有 dist 时，构建状态维持 idle")
    void vueAppWithoutDistShouldStayIdle() throws Exception {
        Files.createDirectories(vueSourceDir(VUE_APP_WITHOUT_DIST).resolve("src"));

        GenerationStatusVO vo = invokeNoTaskStatus(VUE_APP_WITHOUT_DIST, vueApp(VUE_APP_WITHOUT_DIST));

        assertEquals("idle", vo.getBuildStatus());
    }

    /** 非 Vue 模式（HTML / 多文件）不涉及异步构建，维持 idle */
    @Test
    @DisplayName("非 Vue 工程模式不做构建状态推导")
    void htmlAppShouldStayIdle() throws Exception {
        App app = new App();
        app.setId(HTML_APP_WITHOUT_DIST);
        app.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        // 即使目录里恰好有 dist，也不应该影响非 Vue 模式
        Path dist = Files.createDirectories(
                sourceDir(CodeGenTypeEnum.MULTI_FILE.getValue(), HTML_APP_WITHOUT_DIST).resolve("dist"));
        Files.writeString(dist.resolve("index.html"), "<!doctype html><html></html>");

        GenerationStatusVO vo = invokeNoTaskStatus(HTML_APP_WITHOUT_DIST, app);

        assertEquals("idle", vo.getBuildStatus());
        assertEquals("none", vo.getStatus());
    }

    /**
     * 反射调用私有的 {@code noTaskStatus(Long, App)}
     */
    private GenerationStatusVO invokeNoTaskStatus(Long appId, App app) throws Exception {
        Method method = AppServiceImpl.class.getDeclaredMethod("noTaskStatus", Long.class, App.class);
        method.setAccessible(true);
        return (GenerationStatusVO) method.invoke(appService, appId, app);
    }
}
