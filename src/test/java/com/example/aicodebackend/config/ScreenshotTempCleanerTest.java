package com.example.aicodebackend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截图临时目录自动清理的单元测试
 * <p>
 * 关注的语义：过期的截图目录要被回收，正在使用（未过期）的产物绝不能被误删，
 * 并且清理范围只限于截图目录本身（生成的应用源码目录不归它管）。
 */
class ScreenshotTempCleanerTest {

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("screenshot-cleaner-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** 在根目录下造一个"截图目录 + 压缩图"，可指定文件的修改时间 */
    private File createScreenshotEntry(String dirName, String fileName, long ageMinutes) throws IOException {
        Path dir = Files.createDirectory(root.resolve(dirName));
        Path file = Files.write(dir.resolve(fileName), new byte[16]);
        FileTime aged = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ageMinutes));
        Files.setLastModifiedTime(file, aged);
        Files.setLastModifiedTime(dir, aged);
        return dir.toFile();
    }

    /** 过期残留必须被回收 */
    @Test
    void shouldDeleteExpiredScreenshotEntries() throws Exception {
        File expired = createScreenshotEntry("expired01", "12345_compressed.jpg", 120);
        ScreenshotTempCleaner cleaner = new ScreenshotTempCleaner(root.toFile(), 60, 10);

        int removed = cleaner.sweep("测试");

        assertEquals(1, removed);
        assertFalse(expired.exists(), "超过 TTL 的截图目录必须被删除");
    }

    /** 正在使用（未过期）的截图产物不能被误删 */
    @Test
    void shouldKeepFreshScreenshotEntries() throws Exception {
        File fresh = createScreenshotEntry("fresh001", "12345_compressed.jpg", 1);
        ScreenshotTempCleaner cleaner = new ScreenshotTempCleaner(root.toFile(), 60, 10);

        int removed = cleaner.sweep("测试");

        assertEquals(0, removed);
        assertTrue(fresh.exists(), "未过期的截图产物必须保留（可能正在上传 COS）");
    }

    /** 过期条目里的空目录（截图失败留下的残骸）同样要被回收 */
    @Test
    void shouldDeleteExpiredEmptyDirectories() throws Exception {
        Path empty = Files.createDirectory(root.resolve("empty001"));
        Files.setLastModifiedTime(empty, FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(120)));
        ScreenshotTempCleaner cleaner = new ScreenshotTempCleaner(root.toFile(), 60, 10);

        assertEquals(1, cleaner.sweep("测试"));
        assertFalse(Files.exists(empty));
    }

    /** 根目录不存在时不能抛异常（例如从未截图过的环境） */
    @Test
    void shouldHandleMissingRootDirectory() {
        ScreenshotTempCleaner cleaner = new ScreenshotTempCleaner(new File(root.toFile(), "not-exists"), 60, 10);
        assertEquals(0, cleaner.sweep("测试"));
    }

    /** 清理范围只限于根目录下的条目：不能越界删除 */
    @Test
    void shouldOnlyTouchEntriesUnderRoot() throws Exception {
        File expired = createScreenshotEntry("expired02", "12345_compressed.jpg", 120);
        // 兄弟目录（模拟 temp/code_output 这类用户数据）必须原样保留
        Path userData = Files.createDirectory(root.getParent().resolve("user-data-" + root.getFileName()));
        try {
            ScreenshotTempCleaner cleaner = new ScreenshotTempCleaner(root.toFile(), 60, 10);

            assertEquals(1, cleaner.sweep("测试"));

            assertFalse(expired.exists());
            assertTrue(Files.exists(userData), "截图目录之外的内容不允许被清理");
        } finally {
            Files.deleteIfExists(userData);
        }
    }
}
