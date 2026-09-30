package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.HTMLCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML代码解析器
 */
public class HTMLCodeParser implements CodeParser<HTMLCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)(?:```|\\z)", Pattern.CASE_INSENSITIVE);

    @Override
    public HTMLCodeResult parserCode(String codeContent) {
        HTMLCodeResult result = new HTMLCodeResult();
        // 提取 HTML 代码
        String htmlCode = extractHtmlCode(codeContent);
        if (htmlCode != null && !htmlCode.trim().isEmpty()) {
            result.setHtmlCode(htmlCode.trim());
        } else {
            // 如果没有找到代码块，将整个内容作为HTML
            result.setHtmlCode(codeContent.trim());
        }
        return result;
    }

    /**
     * 提取HTML代码内容
     *
     * @param content 原始内容
     * @return HTML代码
     */
    private static String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
