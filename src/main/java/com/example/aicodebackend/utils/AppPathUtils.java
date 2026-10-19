package com.example.aicodebackend.utils;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.model.enums.GeneratedFileEnum;

import java.nio.file.Path;

/** 生成产物与部署目录的路径约定 */
public final class AppPathUtils {

    public static final String VUE_PROJECT_PREFIX = "vue_project_";

    public static final String DIST_DIR_NAME = "dist";

    private AppPathUtils() {
    }

    /** 某个应用的源码目录（{codeGenType_appId}） */
    public static Path projectDir(String codeGenType, Long appId) {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenType + "_" + appId);
    }

    public static String projectDirPath(String codeGenType, Long appId) {
        return projectDir(codeGenType, appId).toString();
    }

    /** Vue 工程模式的应用目录 */
    public static Path vueProjectDir(Long appId) {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, VUE_PROJECT_PREFIX + appId);
    }

    /** Vue 工程构建产物目录 */
    public static Path distDir(String codeGenType, Long appId) {
        return projectDir(codeGenType, appId).resolve(DIST_DIR_NAME);
    }

    /** Vue 工程构建产物入口文件 */
    public static Path distIndex(String codeGenType, Long appId) {
        return distDir(codeGenType, appId).resolve(GeneratedFileEnum.HTML.getFileName());
    }

    /** 应用的对外访问地址，deployKey 为空时返回 null */
    public static String deployUrl(String deployHost, String deployKey) {
        return StrUtil.isBlank(deployKey) ? null : deployHost + "/" + deployKey + "/";
    }

    /** 截图临时根目录 */
    public static Path screenshotTempDir() {
        return Path.of(AppConstant.TEMP_ROOT_DIR, AppConstant.SCREENSHOT_DIR_NAME);
    }
}
