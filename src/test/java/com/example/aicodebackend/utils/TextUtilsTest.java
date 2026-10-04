package com.example.aicodebackend.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本工具测试：落库兜底截断 + 进入模型上下文前压缩代码块
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

    @Test
    @DisplayName("压缩代码块：保留文件路径与代码块外的文字，代码块只留预览")
    void shouldCompressCodeBlockAndKeepSurroundingText() {
        String code = "a".repeat(1000);
        String text = "已生成登录页面\n[🔧 工具调用] 写入文件 src/Login.vue\n```vue\n" + code + "\n```\n写入完成";

        String compressed = TextUtils.compressCodeBlocks(text, 100);

        assertTrue(compressed.contains("[🔧 工具调用] 写入文件 src/Login.vue"), "应保留文件路径");
        assertTrue(compressed.contains("已生成登录页面") && compressed.contains("写入完成"), "应保留代码块之外的文字");
        assertTrue(compressed.contains("```vue"), "应保留代码块起始标记");
        assertTrue(compressed.contains("已省略 900 字符源码"), "应说明省略了多少源码");
        assertTrue(compressed.length() < text.length(), "压缩后应明显变短");
    }

    @Test
    @DisplayName("多个代码块会被逐个压缩")
    void shouldCompressEveryCodeBlock() {
        String text = "```js\n" + "x".repeat(500) + "\n```\n中间说明\n```css\n" + "y".repeat(500) + "\n```";

        String compressed = TextUtils.compressCodeBlocks(text, 50);

        assertTrue(compressed.contains("中间说明"), "应保留代码块之间的文字");
        assertEquals(2, compressed.split("字符源码", -1).length - 1, "两个代码块都应被压缩");
    }

    @Test
    @DisplayName("短代码块与无代码块的文本原样返回")
    void shouldKeepShortCodeBlockAndPlainText() {
        String shortBlock = "说明\n```java\nint a = 1;\n```\n结束";
        assertEquals(shortBlock, TextUtils.compressCodeBlocks(shortBlock, 100));
        assertEquals("没有代码块", TextUtils.compressCodeBlocks("没有代码块", 100));
        assertNull(TextUtils.compressCodeBlocks(null, 100));
    }
}
