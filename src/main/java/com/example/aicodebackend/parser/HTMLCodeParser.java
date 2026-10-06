package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 代码解析器
 * <p>
 * 容错点（本次修复）：
 * <ol>
 *     <li>代码块围栏允许 {@code ```html} 之外的空格、大小写、以及未闭合（模型截断）；</li>
 *     <li>代码块缺失时回退到"整段内容"，并从文本里定位 {@code <!DOCTYPE} / {@code <html} 起点，
 *     丢掉模型在代码块之外写的解释文字；</li>
 *     <li>修复标签名与属性之间空格被吞掉的问题（见 {@link GeneratedCodeRepair}）——
 *     否则内联的 CSS / JavaScript 会因为标签解析失败而完全不生效。</li>
 * </ol>
 */
@Slf4j
public class HTMLCodeParser implements CodeParser<HTMLCodeResult> {

    /**
     * 兜底提取：整段内容里没有代码块时，从第一个 HTML 起始标记开始截取
     */
    private static final Pattern HTML_START_PATTERN =
            Pattern.compile("<!doctype\\s+html|<html[\\s>]", Pattern.CASE_INSENSITIVE);

    @Override
    public HTMLCodeResult parserCode(String codeContent) {
        HTMLCodeResult result = new HTMLCodeResult();
        String content = codeContent == null ? "" : codeContent;
        String htmlCode = extractHtmlCode(content);
        if (htmlCode == null || htmlCode.isBlank()) {
            // 没有代码块或代码块为空：回退到整段内容（尽量从 HTML 起始标记开始，丢掉解释文字）
            htmlCode = fallbackToWholeContent(content);
        }
        if (htmlCode != null && !htmlCode.isBlank()) {
            String trimmed = htmlCode.trim();
            if (GeneratedCodeRepair.hasGluedTags(trimmed)) {
                log.warn("检测到 HTML 标签与属性粘连（模型输出空格丢失），已自动修复");
                trimmed = GeneratedCodeRepair.repairHtml(trimmed);
            }
            result.setHtmlCode(trimmed);
        }
        return result;
    }

    /**
     * 提取 HTML 代码内容
     *
     * @param content 原始内容
     *
     * @return HTML 代码，未找到返回 null
     */
    private static String extractHtmlCode(String content) {
        Map<String, String> blocks = GeneratedCodeRepair.extractCodeBlocks(content);
        for (String alias : new String[]{"html", "htm", "xhtml"}) {
            String block = blocks.get(alias);
            if (block != null && !block.isBlank()) {
                return block;
            }
        }
        // 模型偶尔会用 text / xml 标注 HTML 代码块
        for (String alias : new String[]{"text", "xml", "plaintext"}) {
            String block = blocks.get(alias);
            if (block != null && block.contains("<") && !block.isBlank()) {
                return block;
            }
        }
        return null;
    }

    /**
     * 兜底：没有可识别的代码块时，从 HTML 起始标记截取到结尾
     *
     * @param content 原始内容
     *
     * @return 截取后的内容（找不到起始标记时返回原文）
     */
    private static String fallbackToWholeContent(String content) {
        Matcher matcher = HTML_START_PATTERN.matcher(content);
        if (matcher.find()) {
            return content.substring(matcher.start());
        }
        return content;
    }
}
