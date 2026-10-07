package com.example.aicodebackend.config;

import cn.hutool.core.io.FileUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 截图临时目录（{@code temp/screenshots}）的自动清理
 * <p>
 * 为什么需要它：截图产物是"上传 COS 之后就没用了"的中间文件，正常路径由
 * {@code ScreenshotServiceImpl#generateAndUploadScreenshot} 在 finally 里删除。
 * 但这条路径覆盖不到几种情况，实测都会留下残骸：
 * <ul>
 *     <li>直接调用 {@code WebScreenshotUtils} 的调用方（例如单测）绕过 Service，没人删；</li>
 *     <li>上传过程中进程被杀 / 容器被重启，finally 没机会执行；</li>
 *     <li>截图流程中途失败（旧实现连中间产物都不删，现已在该工具内自清理）。</li>
 * </ul>
 * 因此这里做一层兜底：<b>启动时扫一遍 + 之后定时扫</b>，把超过 TTL 的截图目录当作垃圾回收。
 * <p>
 * 边界（很重要）：只清理 {@code temp/screenshots} 下的内容。
 * {@code temp/code_output}（生成的应用源码）与 {@code temp/code_deploy}（部署产物）是用户数据，
 * 只能由业务逻辑删除，绝不能被这里"顺手"清掉。
 */
@Slf4j
@Component
public class ScreenshotTempCleaner implements ApplicationRunner {

    /**
     * 截图临时根目录（与 {@code WebScreenshotUtils} 保持一致）
     */
    static final String SCREENSHOT_TEMP_DIR =
            System.getProperty("user.dir") + File.separator + "temp" + File.separator + "screenshots";

    /**
     * 过期阈值：超过这个时间还没被业务删除的截图目录，视为残留垃圾
     * <p>
     * 默认 60 分钟远大于"截图 + 上传 COS"的耗时（秒级），因此不会误删正在上传的产物。
     */
    private final long ttlMillis;

    /**
     * 清理间隔（毫秒）
     */
    private final long sweepIntervalMillis;

    /**
     * 待清理的根目录
     */
    private final File rootDir;

    /**
     * 定时清扫线程：单线程守护线程，不参与任何业务
     */
    private ScheduledExecutorService sweeper;

    /**
     * 容器使用的构造器
     * <p>
     * 必须显式标注 {@link Autowired}：类里还有一个供单测使用的构造器，
     * 两个构造器并存时 Spring 不会自己挑（实测直接报 No default constructor found）。
     *
     * @param ttlMinutes   过期阈值（分钟），配置项 {@code screenshot.temp.ttl-minutes}
     * @param sweepMinutes 清理间隔（分钟），配置项 {@code screenshot.temp.sweep-minutes}
     */
    @Autowired
    public ScreenshotTempCleaner(@Value("${screenshot.temp.ttl-minutes:60}") long ttlMinutes,
                                 @Value("${screenshot.temp.sweep-minutes:10}") long sweepMinutes) {
        this(new File(SCREENSHOT_TEMP_DIR), ttlMinutes, sweepMinutes);
    }

    /**
     * 供单测直接指定目录 / 阈值使用
     *
     * @param rootDir       截图临时根目录
     * @param ttlMinutes    过期阈值（分钟）
     * @param sweepMinutes  清理间隔（分钟）
     */
    ScreenshotTempCleaner(File rootDir, long ttlMinutes, long sweepMinutes) {
        this.rootDir = rootDir;
        this.ttlMillis = TimeUnit.MINUTES.toMillis(Math.max(0, ttlMinutes));
        this.sweepIntervalMillis = TimeUnit.MINUTES.toMillis(Math.max(1, sweepMinutes));
    }

    /**
     * 应用启动后：先扫一遍历史残留，再按间隔定时扫
     * <p>
     * 用守护线程而不是 {@code @Scheduled}：本项目没有开启 Spring 定时任务，
     * 为一个"清垃圾"的后台动作去全局打开 {@code @EnableScheduling} 不划算。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        sweep("启动");
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "screenshot-temp-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(() -> sweep("定时"), sweepIntervalMillis, sweepIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("截图临时目录清理已启动：目录={}, 过期阈值={}分钟, 清理间隔={}分钟",
                rootDir.getAbsolutePath(), TimeUnit.MILLISECONDS.toMinutes(ttlMillis),
                TimeUnit.MILLISECONDS.toMinutes(sweepIntervalMillis));
    }

    /**
     * 清理一次过期残留
     * <p>
     * 判定依据是"条目及其子文件里最新的修改时间"：截图目录里只有一个压缩图，创建即定稿；
     * 取最大值可以避免"目录 mtime 比文件旧"这类边界把正在写的产物误判成垃圾。
     *
     * @param trigger 触发来源（仅用于日志）
     *
     * @return 被删除的条目数
     */
    int sweep(String trigger) {
        if (!rootDir.isDirectory()) {
            return 0;
        }
        File[] children = rootDir.listFiles();
        if (children == null || children.length == 0) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - ttlMillis;
        int removed = 0;
        long removedBytes = 0;
        for (File child : children) {
            if (newestModified(child) >= deadline) {
                continue;
            }
            long size = child.isFile() ? child.length() : FileUtil.size(child);
            if (FileUtil.del(child)) {
                removed++;
                removedBytes += size;
            }
        }
        if (removed > 0) {
            log.info("截图临时目录清理完成（{}）：删除 {} 个过期条目，释放 {} KB",
                    trigger, removed, removedBytes / 1024);
        }
        return removed;
    }

    /**
     * 取条目及其一级子文件中最新的修改时间（毫秒）
     *
     * @param file 条目
     *
     * @return 最新修改时间
     */
    private static long newestModified(File file) {
        long newest = file.lastModified();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    newest = Math.max(newest, child.lastModified());
                }
            }
        }
        return newest;
    }

    /**
     * 应用关闭：停止清扫线程
     */
    @PreDestroy
    public void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
    }
}
