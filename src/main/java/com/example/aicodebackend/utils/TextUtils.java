package com.example.aicodebackend.utils;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具类
 * <p>
 * 两个用途：
 * <ul>
 *     <li>落库时兜底限制异常巨大的文本（{@link #truncate}）；</li>
 *     <li>把对话历史写入模型上下文前压缩其中的代码块（{@link #compressCodeBlocks}），
 *     避免完整工程源码参与多轮对话。</li>
 * </ul>
 */
public final class TextUtils {

    /**
     * Markdown 代码块：开头的 ``` 行（可带语言标识）+ 内容 + 独占一行的结尾 ```
     */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?m)^(```[^\\n]*\\n)([\\s\\S]*?)(^```[ \\t]*$)");

    private TextUtils() {
    }

    /**
     * 按最大长度截断文本，超长部分被丢弃并追加截断说明
     *
     * @param text      原始文本
     * @param maxLength 允许保留的最大字符数（不包含截断说明本身）
     *
     * @return 截断后的文本；未超长或无需截断时原样返回
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return StrUtil.subPre(text, maxLength)
                + "\n\n...（内容过长，已省略 " + (text.length() - maxLength) + " 字符，原文共 " + text.length() + " 字符）";
    }

    /**
     * 压缩文本中的 Markdown 代码块，只保留每个代码块的开头预览
     * <p>
     * 代码块之外的文字（AI 的说明、写入的文件路径等）原样保留，
     * 因此压缩后的内容仍然能表达「聊了什么、写了哪些文件」，但不会把完整源码带进模型上下文。
     *
     * @param text           原始文本
     * @param blockMaxLength 单个代码块保留的最大字符数
     *
     * @return 压缩后的文本；文本为空或无代码块时原样返回
     */
    public static String compressCodeBlocks(String text, int blockMaxLength) {
        if (StrUtil.isEmpty(text) || blockMaxLength <= 0) {
            return text;
        }
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        StringBuilder compressed = new StringBuilder();
        while (matcher.find()) {
            // 结尾换行属于代码块语法结构，不计入代码内容长度，避免省略字符数出现 +1 的偏移
            String body = StrUtil.removeSuffix(matcher.group(2), "\n");
            String compressedBody = body.length() <= blockMaxLength
                    ? matcher.group(2)
                    : StrUtil.subPre(body, blockMaxLength)
                    + "\n...（已省略 " + (body.length() - blockMaxLength) + " 字符源码）\n";
            matcher.appendReplacement(compressed,
                    Matcher.quoteReplacement(matcher.group(1) + compressedBody + matcher.group(3)));
        }
        matcher.appendTail(compressed);
        return compressed.toString();
    }
}
