package com.example.aicodebackend.ai.tools;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.utils.AppPathUtils;

import java.io.Serial;
import java.nio.file.Path;

/** 文件类工具的公共能力：项目目录解析、越界保护与展示文案 */
public abstract class AbstractFileTool extends BaseTool {

    /** 工具调用展示的固定前缀 */
    protected static final String TOOL_CALL_PREFIX = "[🔧 工具调用]";

    /** 相对路径解析到应用目录；绝对路径必须落在应用目录内 */
    protected Path resolveProjectPath(String rawPath, Long appId) {
        Path projectRoot = AppPathUtils.vueProjectDir(appId).toAbsolutePath().normalize();
        Path path = Path.of(StrUtil.nullToEmpty(rawPath));
        path = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        if (!path.startsWith(projectRoot)) {
            throw new PathOutsideProjectException(rawPath, projectRoot);
        }
        return path;
    }

    /** 项目根目录（目录读取等需要返回相对结构的场景） */
    protected Path projectRoot(Long appId) {
        return AppPathUtils.vueProjectDir(appId).toAbsolutePath().normalize();
    }

    /** 工具调用标题行 */
    protected String toolCallHeader(String target) {
        return String.format("%s %s %s", TOOL_CALL_PREFIX, getDisplayName(), StrUtil.nullToEmpty(target));
    }

    /** 路径越界异常（由各工具翻译成自己的返回文案） */
    protected static final class PathOutsideProjectException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String projectRoot;

        private PathOutsideProjectException(String rawPath, Path projectRoot) {
            super("路径不在项目目录内：" + rawPath);
            this.projectRoot = projectRoot.toString();
        }

        public String projectRoot() {
            return projectRoot;
        }
    }
}
