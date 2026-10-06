package com.example.aicodebackend.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网页截图工具
 * <p>
 * 并发模型（本次修复的核心）：
 * 旧实现是全应用共享<b>一个</b> {@code static final WebDriver}，所有截图请求排队复用同一个浏览器实例：
 * 100 个应用并发部署时，最后一张截图比第一个部署请求晚近一分钟，而且并发的 {@code get()} 会互相打断，
 * 存在"A 应用截到 B 应用页面"的串号风险；一旦 Chrome 崩溃，整个实例的截图能力全部不可用。
 * <p>
 * 现在改为<b>有界浏览器池</b>：
 * <ul>
 *     <li>最多 {@value #MAX_DRIVERS} 个实例，按需创建、用完归还，每个实例同一时刻只服务一个请求；</li>
 *     <li>取不到实例时最多等待 {@value #BORROW_TIMEOUT_SECONDS} 秒，超时给出明确失败，而不是无限堆积；</li>
 *     <li>截图过程中出错的实例直接销毁，下次按需重建，单个浏览器崩溃不会拖垮整个服务；</li>
 *     <li>空闲实例在超过 {@value #IDLE_EVICT_MILLIS} ms 未使用后被回收，避免长期占用内存。</li>
 * </ul>
 */
@Slf4j
public class WebScreenshotUtils {

    /**
     * 浏览器实例上限：Chrome 是重资源，按机器核数与内存给出保守上限
     */
    private static final int MAX_DRIVERS = Integer.getInteger("screenshot.max-drivers", 6);

    /**
     * 借出浏览器的最长等待时间（秒）
     * <p>
     * 100 个应用并发部署会产生 100 个截图任务，单张截图约 3 秒，池化后必然排队：
     * 超时过小会让排队中的任务直接失败（实测 30 秒 + 池大小 4 时，100 个任务里 71 个失败）。
     * 这里按"最坏情况 ≈ 任务数 × 单张耗时 / 池大小"留出余量，并通过 JVM 参数可调。
     */
    private static final int BORROW_TIMEOUT_SECONDS = Integer.getInteger("screenshot.borrow-timeout-seconds", 180);

    /**
     * 空闲实例的保留时长（毫秒），超过则关闭，避免浏览器实例长期驻留
     */
    private static final long IDLE_EVICT_MILLIS = 5 * 60 * 1000L;

    private static final int DEFAULT_WIDTH = 1600;

    private static final int DEFAULT_HEIGHT = 900;

    /**
     * 空闲实例池（每个实例同一时刻只被一个线程持有）
     */
    private static final LinkedBlockingDeque<PooledDriver> IDLE_DRIVERS = new LinkedBlockingDeque<>(MAX_DRIVERS);

    private static final Object POOL_LOCK = new Object();

    /**
     * 已创建的实例总数（含借出中），用于控制总量不超过 MAX_DRIVERS
     */
    private static final AtomicInteger CREATED_DRIVERS = new AtomicInteger();

    static {
        // 进程退出时统一关闭浏览器，避免留下 chrome 僵尸进程
        Runtime.getRuntime().addShutdownHook(new Thread(WebScreenshotUtils::closeAll, "screenshot-shutdown"));
    }

    @PreDestroy
    public void destroy() {
        closeAll();
    }

    /**
     * 生成网页截图
     *
     * @param webUrl 网页URL
     *
     * @return 压缩后的截图文件路径，失败返回null
     */
    public static String saveWebPageScreenshot(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页URL不能为空");
            return null;
        }
        PooledDriver pooledDriver = null;
        boolean healthy = false;
        try {
            pooledDriver = borrowDriver();
            WebDriver driver = pooledDriver.driver;
            // 创建临时目录
            String rootPath = System.getProperty("user.dir") + File.separator + "temp" + File.separator + "screenshots"
                    + File.separator + UUID.randomUUID().toString().substring(0, 8);
            FileUtil.mkdir(rootPath);
            // 图片后缀
            final String IMAGE_SUFFIX = ".png";
            // 原始截图文件路径
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + IMAGE_SUFFIX;
            // 访问网页
            driver.get(webUrl);
            // 等待页面加载完成
            waitForPageLoad(driver);
            // 截图
            byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            // 保存原始图片
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功: {}", imageSavePath);
            // 压缩图片
            final String COMPRESSION_SUFFIX = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + COMPRESSION_SUFFIX;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功: {}", compressedImagePath);
            // 删除原始图片，只保留压缩图片
            FileUtil.del(imageSavePath);
            healthy = true;
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败: {}", webUrl, e);
            return null;
        } finally {
            returnDriver(pooledDriver, healthy);
        }
    }

    /**
     * 借出一个浏览器实例：优先复用空闲实例，池未满则新建，否则等待其它请求归还
     *
     * @return 池化的浏览器实例
     */
    private static PooledDriver borrowDriver() {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BORROW_TIMEOUT_SECONDS);
        while (true) {
            PooledDriver idle = takeIdle();
            if (idle != null) {
                return idle;
            }
            synchronized (POOL_LOCK) {
                if (CREATED_DRIVERS.get() < MAX_DRIVERS) {
                    CREATED_DRIVERS.incrementAndGet();
                    try {
                        return new PooledDriver(initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT));
                    } catch (RuntimeException e) {
                        CREATED_DRIVERS.decrementAndGet();
                        throw e;
                    }
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图服务繁忙，请稍后重试");
            }
            // 所有实例都在忙：短暂等待后重试（归还会把实例放回空闲队列）
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取截图浏览器被中断");
            }
        }
    }

    /**
     * 从空闲队列取一个实例，并顺带回收闲置过久的实例
     *
     * @return 可用的浏览器实例，池空时返回 null
     */
    private static PooledDriver takeIdle() {
        PooledDriver pooled = IDLE_DRIVERS.pollFirst();
        while (pooled != null && System.currentTimeMillis() - pooled.lastUsedAt > IDLE_EVICT_MILLIS) {
            log.info("回收空闲过久的 WebDriver 实例");
            closeQuietly(pooled.driver);
            CREATED_DRIVERS.decrementAndGet();
            pooled = IDLE_DRIVERS.pollFirst();
        }
        return pooled;
    }

    /**
     * 归还浏览器实例
     *
     * @param pooled  池化实例，可能为 null
     * @param healthy 本次使用是否正常（异常时销毁该实例，避免复用坏实例）
     */
    private static void returnDriver(PooledDriver pooled, boolean healthy) {
        if (pooled == null) {
            return;
        }
        if (!healthy) {
            closeQuietly(pooled.driver);
            CREATED_DRIVERS.decrementAndGet();
            return;
        }
        pooled.lastUsedAt = System.currentTimeMillis();
        if (!IDLE_DRIVERS.offerFirst(pooled)) {
            // 池满（理论不会发生）：直接销毁
            closeQuietly(pooled.driver);
            CREATED_DRIVERS.decrementAndGet();
        }
    }

    /**
     * 关闭所有浏览器实例
     */
    private static void closeAll() {
        PooledDriver pooled;
        while ((pooled = IDLE_DRIVERS.pollFirst()) != null) {
            closeQuietly(pooled.driver);
        }
        CREATED_DRIVERS.set(0);
    }

    private static void closeQuietly(WebDriver driver) {
        try {
            driver.quit();
        } catch (Exception e) {
            log.warn("关闭 WebDriver 失败: {}", e.getMessage());
        }
    }

    /**
     * 池化实例：记录最后一次使用时间，用于空闲回收
     */
    private static final class PooledDriver {
        private final WebDriver driver;
        private volatile long lastUsedAt;

        private PooledDriver(WebDriver driver) {
            this.driver = driver;
            this.lastUsedAt = System.currentTimeMillis();
        }
    }

    /**
     * 初始化 ChromeDriver
     *
     * @param width  默认宽度
     * @param height 默认高度
     *
     * @return WebDriver 实例
     */
    private static WebDriver initChromeDriver(int width, int height) {
        try {
            WebDriverManager.chromedriver().setup(); // 自动下载和配置 ChromeDriver
            ChromeDriver chromeDriver = getChromeDriver(width, height);
            chromeDriver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30)); // 设置页面加载超时时间
            chromeDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10)); // 设置隐式等待时间
            return chromeDriver;
        } catch (Exception e) {
            log.error("初始化 ChromeDriver 失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 ChromeDriver 失败");
        }
    }

    /**
     * 获取 ChromeDriver 实例
     *
     * @param width  窗口宽度
     * @param height 窗口高度
     *
     * @return ChromeDriver 实例
     */
    private static @NonNull ChromeDriver getChromeDriver(int width, int height) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // 无头模式
        options.addArguments("--disable-gpu"); // 禁用 GPU 加速
        options.addArguments("--no-sandbox"); // 禁用沙箱模式
        options.addArguments("--disable-dev-shm-usage"); // 禁用 /dev/shm 使用
        options.addArguments("--window-size=" + width + "," + height); // 设置窗口大小
        options.addArguments("--disable-extensions"); // 禁用扩展
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"); // 设置用户代理
        return new ChromeDriver(options);
    }

    /**
     * 保存图片到文件
     */
    private static void saveImage(byte[] imageBytes, String imagePath) {
        try {
            FileUtil.writeBytes(imageBytes, imagePath);
        } catch (Exception e) {
            log.error("保存图片失败: {}", imagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    /**
     * 压缩图片
     */
    private static void compressImage(String originalImagePath, String compressedImagePath) {
        // 压缩图片质量（0.1 = 10% 质量）
        final float COMPRESSION_QUALITY = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originalImagePath),
                    FileUtil.file(compressedImagePath),
                    COMPRESSION_QUALITY
            );
        } catch (Exception e) {
            log.error("压缩图片失败: {} -> {}", originalImagePath, compressedImagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }

    /**
     * 等待页面加载完成
     */
    private static void waitForPageLoad(WebDriver webDriver) {
        try {
            // 创建等待页面加载对象
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
            // 等待 document.readyState 为complete
            wait.until(driver ->
                    Objects.equals(((JavascriptExecutor) driver).executeScript("return document.readyState"), "complete")
            );
            // 额外等待一段时间，确保动态内容加载完成
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常，继续执行截图", e);
        }
    }
}
