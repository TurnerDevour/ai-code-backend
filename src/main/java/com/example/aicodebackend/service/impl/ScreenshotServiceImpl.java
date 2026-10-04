package com.example.aicodebackend.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.manager.CosManager;
import com.example.aicodebackend.service.ScreenshotService;
import com.example.aicodebackend.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager;

    /**
     * 生成截图并上传网页截图到COS
     *
     * @param url 截图的网页地址
     *
     * @return 上传后的截图URL
     */
    @Override
    public String generateAndUploadScreenshot(String url) {
        // 1.校验url是否为空
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.PARAMS_ERROR, "url不能为空");
        log.info("开始生成网页截图，url: {}", url);
        // 2.生成截图
        String screenshotPath = WebScreenshotUtils.saveWebPageScreenshot(url);
        ThrowUtils.throwIf(StrUtil.isBlank(screenshotPath), ErrorCode.SYSTEM_ERROR, "网页截图生成失败");
        log.info("网页截图生成成功，screenshotPath: {}", screenshotPath);
        // 3.上传截图到COS
        try {
            String cosUrl = uploadScreenshotToCos(screenshotPath);
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.SYSTEM_ERROR, "网页截图上传失败");
            log.info("网页截图上传成功，cosUrl: {}", cosUrl);
            return cosUrl;
        } finally {
            // 4. 清理本地截图文件
            cleanupLocalScreenshot(screenshotPath);
        }
    }

    /**
     * 清理本地截图文件
     *
     * @param screenshotPath 截图文件路径
     */
    private void cleanupLocalScreenshot(String screenshotPath) {
        File localFile = new File(screenshotPath);
        if (localFile.exists()) {
            File parentFile = localFile.getParentFile();
            FileUtil.del(parentFile);
            log.info("本地截图文件已清理，screenshotPath: {}", screenshotPath);
        }
    }

    /**
     * 上传截图到COS
     *
     * @param screenshotPath 截图文件路径
     *
     * @return 上传后的截图URL, 如果上传失败返回null
     */
    private String uploadScreenshotToCos(String screenshotPath) {
        // 1. 校验screenshotPath是否为空
        if (StrUtil.isBlank(screenshotPath)) {
            log.error("上传截图到COS失败，screenshotPath为空");
            return null;
        }

        // 2. 校验screenshot文件是否存在
        File screenshotFile = new File(screenshotPath);
        if (!screenshotFile.exists()) {
            log.error("上传截图到COS失败，screenshot文件不存在，screenshotPath: {}", screenshotPath);
            return null;
        }

        // 3. 生成COS对象键
        String filename = UUID.randomUUID().toString().substring(0, 8) + "_compressed.jpg";
        String cosKey = generateScreenshotKey(filename);
        return cosManager.uploadFile(cosKey, screenshotFile);
    }

    /**
     * 生成截图的COS对象键
     *
     * @param filename 截图文件名
     *
     * @return COS对象键
     */
    private String generateScreenshotKey(String filename) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("/screenshots/%s/%s", datePath, filename);
    }
}
