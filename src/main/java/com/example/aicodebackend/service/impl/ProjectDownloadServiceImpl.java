package com.example.aicodebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

@Slf4j
@Service
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    // 忽略的文件和目录名称
    private static final Set<String> IGNORE_NAMES = Set.of(
            ".git",
            ".idea",
            "target",
            "build",
            "dist",
            ".vscode",
            ".env",
            ".mvn",
            ".gradle",
            ".DS_Store",
            "node_modules"
    );
    // 忽略的文件扩展名
    private static final Set<String> IGNORE_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".temp",
            ".bak",
            ".swp",
            ".cache"
    );

    /**
     * 下载项目为zip文件
     *
     * @param projectPath      项目路径
     * @param downloadFilename 下载文件名
     * @param response         HttpServletResponse
     */
    @Override
    public void downloadProjectAsZip(String projectPath, String downloadFilename, HttpServletResponse response) {
        // 1.参数校验
        ThrowUtils.throwIf(StrUtil.isBlank(projectPath), ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFilename), ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        File projectDir = new File(projectPath);
        ThrowUtils.throwIf(!projectDir.exists() || !projectDir.isDirectory(), ErrorCode.PARAMS_ERROR, "项目路径不存在或不是目录");
        ThrowUtils.throwIf(!projectDir.isDirectory(), ErrorCode.PARAMS_ERROR, "项目路径不是目录");
        log.info("下载项目为zip文件，项目路径：{}，下载文件名：{}", projectPath, downloadFilename);
        // 2. 设置响应头
        response.setContentType("application/zip");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s.zip\"", downloadFilename));
        // 3. 文件过滤
        FileFilter fileFilter = file -> getFileFilter(projectDir.toPath(), file.toPath());
        // 4. 压缩并下载
        try {
            ZipUtil.zip(response.getOutputStream(), StandardCharsets.UTF_8, false, fileFilter, projectDir);
            log.info("下载项目为zip文件成功，项目路径：{}，下载文件名：{}", projectPath, downloadFilename);
        } catch (Exception e) {
            log.error("下载项目为zip文件失败，项目路径：{}，下载文件名：{}", projectPath, downloadFilename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载项目为zip文件失败");
        }
    }

    /**
     * 文件过滤器
     *
     * @param projectPath 当前文件路径
     * @param fullPath    项目根路径
     *
     * @return 是否包含在zip中
     */
    private boolean getFileFilter(Path projectPath, Path fullPath) {
        // 1. 获取相对路径
        Path relativePath = projectPath.relativize(fullPath);
        // 2. 过滤掉 不需要的文件和目录
        for (Path path : relativePath) {
            String pathName = path.toString();
            // 检查是否包含在忽略列表中
            if (IGNORE_NAMES.contains(pathName)) {
                return false;
            }
            // 检查文件扩展名
            if (IGNORE_EXTENSIONS.stream().anyMatch(pathName::endsWith)) {
                return false;
            }
        }
        return true;
    }
}
