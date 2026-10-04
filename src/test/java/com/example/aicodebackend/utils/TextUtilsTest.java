package com.example.aicodebackend.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本截断工具测试
 */
class TextUtilsTest {

    @Test
    @DisplayName("未超长（含恰好等于上限）时原样返回")
    void shouldNotTruncateTextWithinLimit() {
        assertEquals("abc", TextUtils.truncate("abc", 5));
        assertEquals("abcde", TextUtils.truncate("abcde", 5));
        assertNull(TextUtils.truncate(null, 5));
    }

    @Test
    @DisplayName("超长时截断并追加截断说明")
    void shouldTruncateAndAppendMarker() {
        String truncated = TextUtils.truncate("a".repeat(100), 10);

        assertTrue(truncated.startsWith("a".repeat(10)), "应保留前 10 个字符");
        assertTrue(truncated.contains("已省略 90 字符"), "应说明省略了多少字符");
        assertTrue(truncated.contains("原文共 100 字符"), "应说明原文长度");
    }
}
