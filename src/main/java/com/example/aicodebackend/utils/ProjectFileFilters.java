package com.example.aicodebackend.utils;

import java.nio.file.Path;
import java.util.Set;

/** 工程目录中无需参与列举、打包的临时文件规则 */
public final class ProjectFileFilters {

    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", "target", ".mvn", ".gradle",
            ".idea", ".vscode", ".env", ".DS_Store", "coverage"
    );

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".temp", ".bak", ".swp", ".cache", ".lock"
    );

    private ProjectFileFilters() {
    }

    /** 相对路径中任意一级命中忽略规则即跳过 */
    public static boolean shouldIgnore(Path relativePath) {
        if (relativePath == null) {
            return false;
        }
        for (Path segment : relativePath) {
            String name = segment.toString();
            if (IGNORED_NAMES.contains(name)) {
                return true;
            }
            if (IGNORED_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                return true;
            }
        }
        return false;
    }
}
