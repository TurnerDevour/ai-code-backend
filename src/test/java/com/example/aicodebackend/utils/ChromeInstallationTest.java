package com.example.aicodebackend.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chromium / ChromeDriver 定位与兼容性检查的回归测试
 * <p>
 * 锁住线上故障的那一种情况：驱动 155、浏览器 154，Selenium 报
 * {@code session not created: This version of ChromeDriver only supports Chrome version 155}，
 * 所有截图（应用封面）整体失败。这里保证"这种组合一定被判为不兼容"，且失败原因能直接指导修复。
 */
class ChromeInstallationTest {

    @Test
    @DisplayName("从 --version 输出里取主版本：驱动/浏览器/Chrome 三种真实格式都要能解析")
    void shouldParseMajorVersion() {
        assertEquals(155, ChromeInstallation.majorVersion("ChromeDriver 155.0.7583.18 (1ef9f18787a)"));
        assertEquals(154, ChromeInstallation.majorVersion(
                "Chromium 154.0.8037.92 Debian 154.0.8037.92-1~deb12u1"));
        assertEquals(131, ChromeInstallation.majorVersion("Google Chrome 131.0.6778.86"));
        assertNull(ChromeInstallation.majorVersion(null), "没有输出时返回 null（不拦）");
        assertNull(ChromeInstallation.majorVersion("chromedriver: command not found"), "解析不出时返回 null（不拦）");
    }

    @Test
    @DisplayName("线上那一对（驱动 155 / 浏览器 154）必须判为不兼容")
    void shouldDetectTheProductionMismatch() {
        Integer driverMajor = ChromeInstallation.majorVersion("ChromeDriver 155.0.7583.18 (abc)");
        Integer browserMajor = ChromeInstallation.majorVersion(
                "Chromium 154.0.8037.92 Debian 154.0.8037.92-1~deb12u1");

        assertFalse(ChromeInstallation.isCompatible(driverMajor, browserMajor),
                "不同主版本必须判为不兼容");
        assertTrue(ChromeInstallation.isCompatible(154, 154), "同主版本必须放行");
    }

    @Test
    @DisplayName("版本解析不出来时不拦：不能因为读不到版本号就让截图整体不可用")
    void shouldNotBlockWhenVersionUnknown() {
        assertTrue(ChromeInstallation.isCompatible(null, 154));
        assertTrue(ChromeInstallation.isCompatible(155, null));
        assertTrue(ChromeInstallation.isCompatible(null, null));
    }

    @Test
    @DisplayName("不匹配的失败原因必须说清版本差异与修法")
    void mismatchMessageShouldBeActionable() {
        Path driver = Path.of("/usr/bin/chromedriver");
        Path browser = Path.of("/usr/bin/chromium");
        String message = ChromeInstallation.mismatchMessage(driver, 155, browser, 154);

        // 用 Path 自身的字符串形式断言：Windows 上跑测试时同一路径会用反斜杠渲染
        assertTrue(message.contains(driver.toString()), "要说清是哪个驱动：" + message);
        assertTrue(message.contains(browser.toString()), "要说清是哪个浏览器：" + message);
        assertTrue(message.contains("155") && message.contains("154"), "要带上两个主版本：" + message);
        assertTrue(message.contains("重建后端镜像"), "要给出首选修法：" + message);
        assertTrue(message.contains("screenshot.chrome-driver-path"), "要给出手工指定路径的口子：" + message);
    }

    @Test
    @DisplayName("系统里没有驱动/浏览器时定位返回 null，而不是抛异常")
    void locatingMustNotThrow() {
        // 具体返回值取决于运行环境（开发机通常没有 /usr/bin/chromedriver），
        // 这里只锁住"找不到不能抛异常"：调用方据此回退 WebDriverManager
        assertDoesNotThrow(ChromeInstallation::findDriver);
        assertDoesNotThrow(ChromeInstallation::findBrowser);
    }
}
