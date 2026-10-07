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

    /**
     * 历史里的工具调用记录：{@code [工具调用] writeToFile {json}}（失败时是 {@code ⚠️ [工具调用失败] ...}）
     * <p>
     * 参数 JSON 是单行（其中的换行都被转义成 {@code \n}），因此按行匹配即可。
     */
    private static final Pattern TOOL_CALL_RECORD_PATTERN =
            Pattern.compile("\\[工具调用(?:失败)?]\\s*(\\w+)\\s*(\\{[^\\n]*)");

    /** 从工具参数里取文件路径（不同工具的字段名不同） */
    private static final Pattern TOOL_FILE_PATH_PATTERN =
            Pattern.compile("\"(?:relativeFilePath|relativePath|relativeDirPath)\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * 把历史里的"工具调用记录"改写成中性描述，再交给模型
     * <p>
     * <b>为什么必须改</b>：工具调用记录是为了「查看对话」可读而写在 AI 消息文本里的
     * （形如 {@code [工具调用] writeToFile {...}}）。这段历史会被加载回模型记忆、
     * 当成"模型自己说过的话"，于是模型学会了照抄这个格式：<b>它开始用文本"假装"调用工具</b>——
     * 打印 {@code [工具调用] writeToFile {...}} 却没有任何 function call，
     * 结果文件一个字都没写、聊天里却显示调用了工具（实测：整轮 0 次工具执行、0 个文件落盘，
     * 但对话里全是"工具调用"）。
     * <p>
     * 这里把它们换成不可能被当成调用格式的中性记录（顺便丢掉巨型入参，省 token）。
     *
     * @param text 历史文本
     *
     * @return 改写后的文本；没有工具调用记录时原样返回
     */
    public static String neutralizeToolCallRecords(String text) {
        if (StrUtil.isEmpty(text)) {
            return text;
        }
        Matcher matcher = TOOL_CALL_RECORD_PATTERN.matcher(text);
        StringBuilder rewritten = new StringBuilder();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String name = matcher.group(1);
            String arguments = matcher.group(2);
            String filePath = "";
            Matcher pathMatcher = TOOL_FILE_PATH_PATTERN.matcher(arguments);
            if (pathMatcher.find()) {
                filePath = pathMatcher.group(1);
            }
            String action = matcher.group().startsWith("[工具调用失败]")
                    ? "执行失败"
                    // 成功与"模型自己编的文本"在历史里无法区分，因此措辞保守：以文件实际内容为准
                    : "处理过";
            String suffix = matcher.group().startsWith("[工具调用失败]") ? "" : "，以文件实际内容为准";
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                    "（系统记录：" + name + " " + action + " "
                            + (filePath.isEmpty() ? "某个文件" : filePath) + suffix + "）"));
        }
        if (!found) {
            return text;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

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
