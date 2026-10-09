package com.example.aicodebackend.utils;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机 Chrome/Chromium 与 ChromeDriver 的定位与兼容性检查
 * <p>
 * 为什么需要它（线上故障）：容器里 apt 装的是<b>同源同版本</b>的 {@code chromium} + {@code chromium-driver}，
 * 但截图代码原来每次都调 {@code WebDriverManager.chromedriver().setup()}，由 WebDriverManager 自己
 * 决定用哪个驱动。一旦它的解析结果与镜像里的浏览器不是同一版本，Selenium 就会抛出
 * <pre>
 * session not created: This version of ChromeDriver only supports Chrome version 155
 * Current browser version is 154.0.8037.92 with binary path /usr/bin/chromium
 * </pre>
 * 表现为所有截图（应用封面）整体失败，而报错信息里只有"驱动支持 155、浏览器是 154"，
 * 看不出该改哪里。
 * <p>
 * 因此把驱动来源收敛成"<b>用系统里已经装好的驱动</b>"，并在启动时校验它与浏览器的<b>主版本一致</b>：
 * <ul>
 *     <li>定位顺序：系统属性 {@code screenshot.chrome-driver-path} → 环境变量 {@code CHROMEDRIVER_BIN}
 *         → {@code /usr/bin/chromedriver} 等常见路径；浏览器同理（{@code screenshot.chrome-binary-path}
 *         → {@code CHROME_BIN} → {@code /usr/bin/chromium} 等）；</li>
 *     <li>版本不匹配时抛出<b>说清原因与修法</b>的业务异常，而不是把 Selenium 的 500 原样抛给用户；</li>
 *     <li>两者都在镜像构建期被 Dockerfile 校验过（主版本必须相同），因此线上不该出现不匹配。</li>
 * </ul>
 * 读不到版本（例如非标准二进制、执行超时）时<b>不拦</b>：不能因为"解析不出版本号"就让截图整体不可用。
 */
@Slf4j
final class ChromeInstallation {

    /**
     * 驱动路径的系统属性（与 {@code WebScreenshotUtils} 里其它截图参数一样，走 {@code -Dscreenshot.*}）
     */
    static final String DRIVER_PATH_PROPERTY = "screenshot.chrome-driver-path";

    /**
     * 浏览器路径的系统属性
     */
    static final String BROWSER_PATH_PROPERTY = "screenshot.chrome-binary-path";

    /**
     * 驱动路径的环境变量（容器里由 {@code docker/entrypoint.sh} 导出）
     */
    static final String DRIVER_PATH_ENV = "CHROMEDRIVER_BIN";

    /**
     * 浏览器路径的环境变量
     */
    static final String BROWSER_PATH_ENV = "CHROME_BIN";

    /**
     * 常见驱动路径：Debian 的 {@code chromium-driver} 包就装在 {@code /usr/bin/chromedriver}
     */
    private static final List<String> DRIVER_CANDIDATES = List.of(
            "/usr/bin/chromedriver",
            "/usr/local/bin/chromedriver");

    /**
     * 常见浏览器路径：Debian 的 {@code chromium} 包装在 {@code /usr/bin/chromium}
     */
    private static final List<String> BROWSER_CANDIDATES = List.of(
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable");

    /**
     * 从版本号里取主版本：{@code ChromeDriver 155.0.7583.18 (abc)} / {@code Chromium 154.0.8037.92 Debian ...}
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.\\d+(?:\\.\\d+)?");

    /**
     * 读取版本号命令的超时（秒）：二进制异常时不能把截图线程挂死
     */
    private static final int VERSION_TIMEOUT_SECONDS = 5;

    private ChromeInstallation() {
    }

    /**
     * 定位 ChromeDriver 可执行文件
     *
     * @return 驱动路径；系统里没有时为 {@code null}（调用方回退 WebDriverManager 自动下载）
     */
    static Path findDriver() {
        return locate(System.getProperty(DRIVER_PATH_PROPERTY),
                System.getenv(DRIVER_PATH_ENV), DRIVER_CANDIDATES, "ChromeDriver");
    }

    /**
     * 定位浏览器可执行文件
     *
     * @return 浏览器路径；定位不到时为 {@code null}（交给 ChromeDriver 自己找）
     */
    static Path findBrowser() {
        return locate(System.getProperty(BROWSER_PATH_PROPERTY),
                System.getenv(BROWSER_PATH_ENV), BROWSER_CANDIDATES, "Chromium/Chrome");
    }

    /**
     * 按"系统属性 → 环境变量 → 常见路径"的顺序定位一个可执行文件
     *
     * @param propertyValue 系统属性值（可为 null/空白）
     * @param envValue      环境变量值（可为 null/空白）
     * @param candidates    常见路径（按序尝试）
     * @param what          日志里用的名字
     *
     * @return 第一个存在且可执行的路径；都没有时为 null
     */
    private static Path locate(String propertyValue, String envValue, List<String> candidates, String what) {
        Path fromProperty = executableFile(propertyValue);
        if (fromProperty != null) {
            return fromProperty;
        }
        if (propertyValue != null && !propertyValue.isBlank()) {
            // 显式配了却不可用必须报出来：静默回退会让"我明明配了"变成排查噩梦
            log.warn("配置的 {} 路径不存在或不可执行，已忽略: {}", what, propertyValue);
        }
        Path fromEnv = executableFile(envValue);
        if (fromEnv != null) {
            return fromEnv;
        }
        for (String candidate : candidates) {
            Path path = executableFile(candidate);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static Path executableFile(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path path = Path.of(rawPath);
        return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
    }

    /**
     * 执行 {@code <binary> --version} 并返回输出
     *
     * @param binary 可执行文件
     *
     * @return 版本输出（已 trim）；执行失败或超时返回 null
     */
    static String readVersion(Path binary) {
        if (binary == null) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("读取版本超时（{} 秒）: {}", VERSION_TIMEOUT_SECONDS, binary);
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("读取版本失败: {}（{}）", binary, e.getMessage());
            return null;
        }
    }

    /**
     * 解析版本输出里的主版本号
     *
     * @param versionOutput 形如 {@code ChromeDriver 155.0.7583.18 (abc)} 的输出
     *
     * @return 主版本号；解析不出时为 null
     */
    static Integer majorVersion(String versionOutput) {
        if (versionOutput == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionOutput);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * 主版本是否兼容
     * <p>
     * ChromeDriver 只支持<b>同主版本</b>的 Chrome/Chromium；任一版本号未知时不拦
     * （读不出版本是"信息不足"，不是"确定不匹配"）。
     *
     * @param driverMajor  驱动主版本（可为 null）
     * @param browserMajor 浏览器主版本（可为 null）
     *
     * @return true 表示可以继续
     */
    static boolean isCompatible(Integer driverMajor, Integer browserMajor) {
        return driverMajor == null || browserMajor == null || driverMajor.equals(browserMajor);
    }

    /**
     * 校验驱动与浏览器主版本一致，不一致时抛出可直接定位的业务异常
     *
     * @param driver  驱动路径（可为 null）
     * @param browser 浏览器路径（可为 null）
     */
    static void verifyCompatible(Path driver, Path browser) {
        if (driver == null || browser == null) {
            // 任一没定位到就没法比：浏览器交给驱动自己找，驱动交给 WebDriverManager
            return;
        }
        Integer driverMajor = majorVersion(readVersion(driver));
        Integer browserMajor = majorVersion(readVersion(browser));
        if (isCompatible(driverMajor, browserMajor)) {
            return;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, mismatchMessage(driver, driverMajor, browser, browserMajor));
    }

    /**
     * 版本不匹配的失败原因（直接给用户看，必须说清"改哪里"）
     *
     * @param driver       驱动路径
     * @param driverMajor  驱动主版本
     * @param browser      浏览器路径
     * @param browserMajor 浏览器主版本
     *
     * @return 失败原因
     */
    static String mismatchMessage(Path driver, Integer driverMajor, Path browser, Integer browserMajor) {
        return "ChromeDriver 与浏览器版本不匹配：" + driver + " 只支持 Chrome " + driverMajor
                + "，而 " + browser + " 是 " + browserMajor
                + "，截图无法进行。请重建后端镜像（Dockerfile 会安装同源的 chromium + chromium-driver，"
                + "并在构建期校验两者主版本一致）；手工指定时用 -Dscreenshot.chrome-driver-path / "
                + "-Dscreenshot.chrome-binary-path（或环境变量 CHROMEDRIVER_BIN / CHROME_BIN）。";
    }

    /**
     * 供日志记录"实际用的是哪一对"（下次排查不必再进容器手动敲两条 --version）
     *
     * @param driver  驱动路径
     * @param browser 浏览器路径
     *
     * @return 形如 {@code chromedriver=/usr/bin/chromedriver (155.0.7583.18); chromium=/usr/bin/chromium (154.0.8037.92)}
     */
    static String describe(Path driver, Path browser) {
        return "chromedriver=" + describeOne(driver) + "; chromium=" + describeOne(browser);
    }

    private static String describeOne(Path binary) {
        if (binary == null) {
            return "未找到";
        }
        String version = readVersion(binary);
        return binary + (version == null ? "（版本未知）" : " (" + version + ")");
    }
}
