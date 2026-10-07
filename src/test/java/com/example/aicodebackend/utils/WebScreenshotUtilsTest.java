package com.example.aicodebackend.utils;

import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class WebScreenshotUtilsTest {

    @Test
    void saveWebPageScreenshot() {

        String url = "https://www.baidu.com";
        String webPageScreenshot = null;
        try {
            webPageScreenshot = WebScreenshotUtils.saveWebPageScreenshot(url);
            Assertions.assertNotNull(webPageScreenshot);
            log.info("Web page screenshot saved at: {}", webPageScreenshot);
        } finally {
            // 工具类只负责产出文件、由调用方负责回收（生产链路由 ScreenshotServiceImpl 上传 COS 后删除）。
            // 用例直接调用工具类，所以必须自己清干净，否则每跑一次 mvn test
            // 就在 temp/screenshots 下留一个 _compressed.jpg（实测残留就是这么来的）。
            if (webPageScreenshot != null) {
                FileUtil.del(new File(webPageScreenshot).getParentFile());
            }
        }
    }
}
