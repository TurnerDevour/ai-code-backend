package com.example.aicodebackend.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.service.ProjectDownloadService;
import com.example.aicodebackend.utils.ProjectFileFilters;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Slf4j
@Service
public class ProjectDownloadServiceImpl implements ProjectDownloadService {

    /** 下载项目为zip文件 */
    @Override
    public void downloadProjectAsZip(String projectPath, String downloadFilename, HttpServletResponse response) {

        ThrowUtils.throwIf(StrUtil.isBlank(projectPath), ErrorCode.PARAMS_ERROR, "项目路径不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(downloadFilename), ErrorCode.PARAMS_ERROR, "下载文件名不能为空");
        File projectDir = new File(projectPath);
        ThrowUtils.throwIf(!projectDir.exists() || !projectDir.isDirectory(), ErrorCode.PARAMS_ERROR, "项目路径不存在或不是目录");
        log.info("下载项目为zip文件，项目路径：{}，下载文件名：{}", projectPath, downloadFilename);

        response.setContentType("application/zip");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Disposition", String.format("attachment; filename=\"%s.zip\"", downloadFilename));

        FileFilter fileFilter = file -> getFileFilter(projectDir.toPath(), file.toPath());

        try {
            ZipUtil.zip(response.getOutputStream(), StandardCharsets.UTF_8, false, fileFilter, projectDir);
            log.info("下载项目为zip文件成功，项目路径：{}，下载文件名：{}", projectPath, downloadFilename);
        } catch (Exception e) {
            log.error("下载项目为zip文件失败，项目路径：{}，下载文件名：{}", projectPath, downloadFilename, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载项目为zip文件失败");
        }
    }

    /** 文件过滤器 */
    private boolean getFileFilter(Path projectPath, Path fullPath) {
        Path relativePath = projectPath.relativize(fullPath);
        return !ProjectFileFilters.shouldIgnore(relativePath);
    }
}
