package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器
 * <p>
 * 容错点（本次修复）：
 * <ol>
 *     <li>不再为每种语言各写一条"非贪婪"正则（那是按原文顺序扫描的，HTML 代码块内部出现的
 *     同名围栏会把后面的内容吃掉），改为先按围栏把 Markdown 切成代码块，再按语言取用；</li>
 *     <li>语言标识兼容 css / scss、js / javascript / mjs / ts 等常见写法，大小写不敏感；</li>
 *     <li>缺少 HTML 代码块时回退到"整段内容"，并从 {@code <!DOCTYPE} / {@code <html} 起截取，
 *     避免把解释文字一起写进 index.html；</li>
 *     <li>修复标签名与属性之间空格被吞掉的问题（见 {@link GeneratedCodeRepair}）——
 *     这种 HTML 里 {@code href} / {@code src} 不生效，表现为"网站没有样式、没有交互"。</li>
 * </ol>
 * <p>
 * 说明：模型漏输出某个代码块（例如只给了 HTML，没给 style.css / script.js）时，
 * 这里只能如实返回缺失；补齐由 {@code AiCodeGeneratorFacade} 的补全重试负责。
 */
@Slf4j
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    /**
     * HTML 起始标记：兜底截取与"像不像一个页面"的校验用
     * <p>
     * {@code \s*} 而不是 {@code \s+}：模型丢空格时 doctype 会被粘成 {@code <!DOCTYPEhtml>}，
     * 用 {@code \s+} 会直接判定"没有 HTML"，于是整次输出被丢掉。
     */
    private static final Pattern HTML_START_PATTERN =
            Pattern.compile("<!doctype\\s*html|<html[\\s>]", Pattern.CASE_INSENSITIVE);

    /**
     * CSS 语言别名
     */
    private static final String[] CSS_ALIASES = GeneratedCodeRepair.CSS_BLOCK_ALIASES;
    /**
     * JavaScript 语言别名
     */
    private static final String[] JS_ALIASES = GeneratedCodeRepair.JS_BLOCK_ALIASES;

    @Override
    public MultiFileCodeResult parserCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        String content = codeContent == null ? "" : codeContent;
        Map<String, String> blocks = GeneratedCodeRepair.extractCodeBlocks(content);
        if (log.isDebugEnabled()) {
            log.debug("多文件解析：识别到的代码块语言={}", blocks.keySet());
        }

        String htmlCode = GeneratedCodeRepair.firstNonBlank(blocks, GeneratedCodeRepair.HTML_BLOCK_ALIASES);
        String cssCode = GeneratedCodeRepair.firstNonBlank(blocks, CSS_ALIASES);
        String jsCode = GeneratedCodeRepair.firstNonBlank(blocks, JS_ALIASES);

        // 结构化输出（LangChain4j 给 POJO 返回类型追加的 JSON 格式要求）：
        // 模型经常直接回 {"htmlCode":"…","cssCode":"…","jsCode":"…"}，而不是提示词要求的 Markdown 代码块。
        // 不认这种形态的话，下面会走"整段内容当 HTML"的兜底，把 JSON 原文写进 index.html。
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);
        if (!answer.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("多文件解析：识别到结构化输出字段 html={}, css={}, js={}",
                        answer.htmlCode() != null, answer.cssCode() != null, answer.jsCode() != null);
            }
            if (htmlCode == null) {
                htmlCode = answer.htmlCode();
            }
            if (cssCode == null) {
                cssCode = answer.cssCode();
            }
            if (jsCode == null) {
                jsCode = answer.jsCode();
            }
        }

        // HTML 兜底：既没有代码块也没有结构化字段时，从整段内容里定位起始标记
        // （结构化字段缺失说明模型这次确实没给 HTML，此时再截取只会把 JSON / 散文写进 index.html）
        if (htmlCode == null && answer.isEmpty()) {
            htmlCode = fallbackHtml(content);
        }

        // 嵌套情形：模型把 ```css / ```javascript 写在 HTML 代码块内部（示例代码、嵌套围栏），
        // 顶层扫描会把后续内容整段吞进 HTML 块。这里在 HTML 块内部再扫一次，捞回 CSS / JS。
        if (htmlCode != null && (cssCode == null || jsCode == null)) {
            Map<String, String> nested = GeneratedCodeRepair.extractCodeBlocks(htmlCode);
            if (cssCode == null) {
                cssCode = GeneratedCodeRepair.firstNonBlank(nested, CSS_ALIASES);
            }
            if (jsCode == null) {
                jsCode = GeneratedCodeRepair.firstNonBlank(nested, JS_ALIASES);
            }
        }

        if (htmlCode == null && cssCode == null && jsCode == null) {
            // 整段内容本身就长得像 HTML 时才兜底（例如模型把 HTML 直接铺在正文里），
            // 否则宁可让上层报"HTML 代码不能为空"，也不要把 JSON / 解释文字当成网页落盘
            if (GeneratedCodeRepair.looksLikeHtmlDocument(content)) {
                log.warn("多文件解析：没有识别到任何代码块，将整段内容作为 HTML 兜底");
                htmlCode = content.trim();
            } else {
                log.warn("多文件解析：没有识别到任何代码块，也没有 HTML 特征，本次不产出 index.html");
            }
        }

        // HTML 修补：标签名与属性粘连会让 link / script 引用失效
        if (htmlCode != null) {
            String trimmed = htmlCode.trim();
            if (GeneratedCodeRepair.hasGluedTags(trimmed)) {
                log.warn("多文件解析：检测到 HTML 标签与属性粘连（模型输出空格丢失），已自动修复");
                trimmed = GeneratedCodeRepair.repairHtml(trimmed);
            }
            if (GeneratedCodeRepair.looksLikeHtmlDocument(trimmed)) {
                result.setHtmlCode(trimmed);
            } else {
                // 只有零散片段时不当作 index.html，避免把一个不完整的页面写进产物目录
                log.warn("多文件解析：HTML 代码块内容不像完整页面，跳过写入 index.html");
            }
        }
        if (cssCode != null && !cssCode.isBlank()) {
            result.setCssCode(cssCode.trim());
        }
        if (jsCode != null && !jsCode.isBlank()) {
            result.setJsCode(jsCode.trim());
        }
        if (result.getCssCode() == null || result.getJsCode() == null) {
            log.warn("多文件解析：本次输出缺少代码块（css={}, js={}），将由门面层尝试补全",
                    result.getCssCode() != null, result.getJsCode() != null);
        }
        return result;
    }

    /**
     * HTML 兜底：从整段内容里定位 HTML 起始标记后截取，避免把解释文字写进 index.html
     *
     * @param content 原始内容
     *
     * @return 截取到的 HTML，未找到起始标记时返回 null
     */
    private static String fallbackHtml(String content) {
        Matcher matcher = HTML_START_PATTERN.matcher(content);
        if (matcher.find()) {
            return content.substring(matcher.start());
        }
        return null;
    }
}
