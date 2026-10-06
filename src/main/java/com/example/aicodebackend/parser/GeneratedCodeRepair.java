package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成代码的容错修复与代码块扫描
 * <p>
 * 背景（实测结论）：即使系统提示词明确要求"输出三个代码块"，模型仍会输出被破坏的 HTML——
 * 标签名与属性之间的空格会被吞掉，例如：
 * <pre>
 * &lt;!DOCTYPEhtml&gt;
 * &lt;metacharset="UTF-8"&gt;
 * &lt;linkrel="stylesheet"href="style.css"&gt;
 * </pre>
 * 这种 HTML 在浏览器里根本不是有效标签，{@code href} / {@code src} 都不会生效，
 * 表现就是"生成出来的网站没有样式、没有交互"，即使 style.css / script.js 已经正确落盘。
 * <p>
 * 这里做的是<b>纯语法层</b>的修复：只在明确能判定为"标签名/属性粘连"的位置补空格，
 * 不猜测、不重写内容，避免把正确的代码改坏。
 */
public final class GeneratedCodeRepair {

    /**
     * 整个标签：从 {@code <} 到最近的 {@code >}
     * <p>
     * 所有修补都只在单个标签内部进行，这样既不会碰到正文文本，也不会把
     * {@code charset="UTF-8"><meta name="viewport"} 这种"跨标签"的内容误判成粘连
     * （实测这是最大的误报来源）。
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^<>]{0,2000}?>", Pattern.DOTALL);

    /**
     * 已知 HTML 标签名：用于消解"标签名与属性名粘连"的歧义（见 {@link #splitKnownTagName}）。
     * <p>
     * 必须包含常见的单字母标签（{@code a} / {@code b} / {@code i} / {@code p} / {@code s} / {@code u}），
     * 否则 {@code <ahref="x">} 这类无法修复；但也因为存在单字母标签，
     * 拆名必须优先匹配"最长的已知标签名"，否则 {@code <scriptsrc="x">} 会被拆成 {@code <s criptsrc="x">}。
     */
    private static final Set<String> KNOWN_TAG_NAMES = Set.of(
            "html", "head", "body", "meta", "link", "title", "style", "script", "base", "noscript",
            "div", "span", "p", "a", "img", "ul", "ol", "li", "dl", "dt", "dd", "nav", "header", "footer",
            "main", "section", "article", "aside", "button", "input", "form", "label", "select", "option",
            "textarea", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "h1", "h2", "h3",
            "h4", "h5", "h6", "br", "hr", "strong", "em", "small", "code", "pre", "iframe", "canvas", "svg",
            "path", "video", "audio", "source", "figure", "figcaption", "blockquote", "i", "b", "u", "s",
            "time", "mark", "abbr", "details", "summary", "dialog", "template", "slot", "picture", "track",
            "fieldset", "legend", "datalist", "output", "progress", "meter", "map", "area", "object", "embed",
            "param", "del", "ins", "q", "cite", "dfn", "kbd", "samp", "var", "sub", "sup", "ruby", "rt", "rp",
            "wbr", "bdi", "bdo", "data", "address", "center", "font", "big", "strike", "tt", "marquee");

    /**
     * 三个文件的键名（解析结果、修复指令、日志统一用它，避免各处硬编码字符串）
     */
    public static final String FILE_HTML = "html";
    public static final String FILE_CSS = "css";
    public static final String FILE_JS = "js";

    /**
     * 代码块语言别名：HTML
     */
    public static final String[] HTML_BLOCK_ALIASES = {"html", "htm", "xhtml"};

    /**
     * 代码块语言别名：CSS（模型偶尔写 scss / less）
     */
    public static final String[] CSS_BLOCK_ALIASES = {"css", "scss", "sass", "less"};

    /**
     * 代码块语言别名：JavaScript（模型偶尔写 ts / mjs）
     */
    public static final String[] JS_BLOCK_ALIASES = {"js", "javascript", "mjs", "cjs", "ts", "typescript"};

    private GeneratedCodeRepair() {
    }

    /**
     * HTML 起始标记
     * <p>
     * {@code \s*} 而不是 {@code \s+}：模型丢空格时 doctype 会被粘成 {@code <!DOCTYPEhtml>}
     */
    private static final Pattern HTML_START_PATTERN =
            Pattern.compile("<!doctype\\s*html|<html[\\s>]", Pattern.CASE_INSENSITIVE);

    /**
     * 判断文本是否像一个完整的 HTML 页面
     *
     * @param text 文本
     *
     * @return true 表示包含 {@code <!DOCTYPE html>} 或 {@code <html …>}
     */
    public static boolean looksLikeHtmlDocument(String text) {
        return StrUtil.isNotBlank(text) && HTML_START_PATTERN.matcher(text).find();
    }

    /**
     * 从模型输出里取三个文件的内容
     * <p>
     * 两种形态都支持，代码块优先（那是系统提示词要求的格式）：
     * <ol>
     *     <li>Markdown 代码块：{@code ```html … ```} / {@code ```css … ```} / {@code ```javascript … ```}；</li>
     *     <li>结构化输出：{@code {"htmlCode":"…","cssCode":"…","jsCode":"…"}}（LangChain4j 对 POJO 返回类型的要求）。</li>
     * </ol>
     *
     * @param content 模型输出的原始文本
     *
     * @return 键为 {@code html} / {@code css} / {@code js} 的有序映射；缺失的文件不会出现在映射里
     */
    public static Map<String, String> extractFiles(String content) {
        Map<String, String> files = new LinkedHashMap<>();
        if (StrUtil.isBlank(content)) {
            return files;
        }
        Map<String, String> blocks = extractCodeBlocks(content);
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);
        putIfNotBlank(files, FILE_HTML,
                firstNonBlank(blocks, HTML_BLOCK_ALIASES), answer.htmlCode());
        putIfNotBlank(files, FILE_CSS,
                firstNonBlank(blocks, CSS_BLOCK_ALIASES), answer.cssCode());
        putIfNotBlank(files, FILE_JS,
                firstNonBlank(blocks, JS_BLOCK_ALIASES), answer.jsCode());
        return files;
    }

    /**
     * 按别名顺序取第一个非空代码块
     *
     * @param blocks  代码块映射
     * @param aliases 语言别名
     *
     * @return 代码内容，未找到返回 null
     */
    public static String firstNonBlank(Map<String, String> blocks, String[] aliases) {
        for (String alias : aliases) {
            String block = blocks.get(alias);
            if (block != null && !block.isBlank()) {
                return block;
            }
        }
        return null;
    }

    private static void putIfNotBlank(Map<String, String> files, String key, String fromBlocks, String fromJson) {
        String value = StrUtil.isNotBlank(fromBlocks) ? fromBlocks : fromJson;
        if (StrUtil.isNotBlank(value)) {
            files.put(key, value);
        }
    }

    /**
     * 修复 HTML 文本里"标签名/属性之间空格被吞掉"的问题
     * <p>
     * 只在确有粘连特征时改动；文本内容（如 &lt;title&gt;简·博客&lt;/title&gt;）不会被影响。
     * 标签之间的空白也保持原样（那属于代码风格，改动风险大于收益）。
     *
     * @param html 原始 HTML 文本
     *
     * @return 修复后的 HTML 文本（无需修复时原样返回）
     */
    public static String repairHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return html;
        }
        String repaired = repairDoctype(html);
        Matcher matcher = TAG_PATTERN.matcher(repaired);
        StringBuilder builder = new StringBuilder(repaired.length() + 32);
        int last = 0;
        while (matcher.find()) {
            builder.append(repaired, last, matcher.start());
            builder.append(repairTag(matcher.group()));
            last = matcher.end();
        }
        builder.append(repaired, last, repaired.length());
        return builder.toString();
    }

    /**
     * 判断 HTML 文本是否存在"空格被吞掉"的粘连特征（用于日志与回归验证）
     *
     * @param html HTML 文本
     *
     * @return true 表示存在粘连
     */
    public static boolean hasGluedTags(String html) {
        return countGluedTags(html) > 0;
    }

    /**
     * 统计存在"标签名/属性粘连"的标签数量
     * <p>
     * 供 {@link CodeDamageDetector} 打分与门面层日志使用：数量比布尔值更能说明损坏范围。
     *
     * @param html HTML 文本
     *
     * @return 粘连标签数量（0 表示没有粘连）
     */
    public static int countGluedTags(String html) {
        if (StrUtil.isBlank(html)) {
            return 0;
        }
        int glued = 0;
        Matcher matcher = TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            if (!repairTag(tag).equals(tag)) {
                glued++;
            }
        }
        return glued;
    }

    /**
     * 修复单个标签内部的空格丢失
     * <p>
     * 逐字符扫描：先跳过 {@code <} / {@code </} / 标签名，然后按"属性名 [= 值]"逐个解析属性，
     * 凡是属性名或属性值<strong>紧贴</strong>在上一段内容之后（中间没有任何空白）就补一个空格。
     * <p>
     * 用扫描而不是正则的原因：正则很难同时表达"标签名与属性名的分割点"和"不改动属性取值"
     * （实测 {@code <span>} 会被误拆成 {@code <s pan>}、合法标签会被补出双空格）。
     * 这里只插入空格、不改写任何字符，因此合法的标签必然保持原样。
     *
     * @param tag 形如 {@code <linkrel="stylesheet"href="style.css">} 的标签
     *
     * @return 修复后的标签（没有粘连时原样返回）
     */
    private static String repairTag(String tag) {
        // 声明 / 注释 / CDATA（<!DOCTYPE ...>、<!-- -->、<![CDATA[ ]]>）不是普通标签，
        // 内部本来就用空格分隔关键字，不做任何修改
        if (tag.startsWith("<!") || tag.startsWith("<?")) {
            return tag;
        }
        StringBuilder builder = new StringBuilder(tag.length() + 16);
        int index = 0;
        builder.append('<');
        index++;
        if (index < tag.length() && tag.charAt(index) == '/') {
            builder.append('/');
            index++;
        }
        // 1) 标签名：标签名与属性名粘连时（htmllang="zh-CN"），按"已知标签名 + 属性名"拆开
        String knownTagName = splitKnownTagName(tag, index);
        if (knownTagName.isEmpty()) {
            // 未知标签名（自定义元素等）：整体搬运
            int nameStart = index;
            while (index < tag.length() && isNameChar(tag.charAt(index))) {
                index++;
            }
            if (index == nameStart) {
                // 既不是属性名也不是标签名（例如 <!DOCTYPE 里的 !）：原样搬运一个字符保证前进
                builder.append(tag.charAt(index));
                index++;
            } else {
                builder.append(tag, nameStart, index);
            }
        } else {
            builder.append(knownTagName);
            index += knownTagName.length();
        }
        // 2) 属性
        while (index < tag.length()) {
            char current = tag.charAt(index);
            if (current == '>') {
                builder.append(tag, index, tag.length());
                return builder.toString();
            }
            if (Character.isWhitespace(current)) {
                builder.append(current);
                index++;
                continue;
            }
            if (current == '/' || current == '=') {
                // 自闭合斜杠 / 多余等号：原样保留
                builder.append(current);
                index++;
                continue;
            }
            // 属性名：与前面紧贴时需要补空格
            if (needsSeparator(tag, index)) {
                builder.append(' ');
            }
            int nameStart = index;
            while (index < tag.length()) {
                char nameChar = tag.charAt(index);
                if (Character.isWhitespace(nameChar) || nameChar == '=' || nameChar == '>'
                        || nameChar == '/' || nameChar == '<' || nameChar == '"' || nameChar == '\'') {
                    break;
                }
                builder.append(nameChar);
                index++;
            }
            if (index == nameStart) {
                // 兜底：这里既不是空白、也不是属性名（例如 <!DOCTYPE 里的 !），
                // 原样搬运一个字符，保证循环一定前进（曾经因为不前进而把 StringBuilder 撑爆）
                builder.append(tag.charAt(index));
                index++;
                continue;
            }
            // 可选空白 + 可选 =
            int valueStart = index;
            while (valueStart < tag.length() && Character.isWhitespace(tag.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart < tag.length() && tag.charAt(valueStart) == '=') {
                builder.append('=');
                index = valueStart + 1;
                // = 后面的空白保留
                int afterEquals = index;
                while (afterEquals < tag.length() && Character.isWhitespace(tag.charAt(afterEquals))) {
                    afterEquals++;
                }
                builder.append(tag, index, afterEquals);
                index = afterEquals;
                if (index < tag.length() && (tag.charAt(index) == '"' || tag.charAt(index) == '\'')) {
                    // 带引号的值：整段搬运，保证取值一个字符都不变
                    char quote = tag.charAt(index);
                    int valueEnd = tag.indexOf(quote, index + 1);
                    valueEnd = valueEnd < 0 ? tag.length() : valueEnd + 1;
                    builder.append(tag, index, valueEnd);
                    index = valueEnd;
                } else {
                    // 不带引号的值：搬运到空白 / > 之前
                    // 反斜杠转义的引号（JSON 文本里的 \" ）属于取值内容，必须一起搬运：
                    // 否则引号会被当成"新属性名的开头"，反而在反斜杠后面补一个空格
                    // （实测把 &lt;html lang=\"zh-CN\"&gt; 改成了 lang=\ " zh-CN\ "）
                    while (index < tag.length()) {
                        char valueChar = tag.charAt(index);
                        if (valueChar == '\\' && index + 1 < tag.length()
                                && (tag.charAt(index + 1) == '"' || tag.charAt(index + 1) == '\'')) {
                            builder.append(valueChar).append(tag.charAt(index + 1));
                            index += 2;
                            continue;
                        }
                        if (Character.isWhitespace(valueChar) || valueChar == '>' || valueChar == '<'
                                || valueChar == '"' || valueChar == '\'') {
                            break;
                        }
                        builder.append(valueChar);
                        index++;
                    }
                }
            }
        }
        return builder.toString();
    }

    /**
     * 判断下标位置上的内容是否与前一个字符"紧贴"（中间没有任何空白或标签起始符）
     *
     * @param tag   标签文本
     * @param index 当前位置
     *
     * @return true 表示需要先补一个空格
     */
    private static boolean needsSeparator(String tag, int index) {
        if (index <= 0) {
            return false;
        }
        char previous = tag.charAt(index - 1);
        return previous != '<' && !Character.isWhitespace(previous);
    }

    /**
     * 从标签起始位置解析标签名：优先取"最长的已知标签名前缀"
     * <p>
     * 为什么要按已知标签名匹配：{@code <htmllang="zh-CN">} 里的 {@code htmllang} 在词法上就是一个
     * 合法标签名，无法用通用规则判断该不该拆。这里用 HTML 标签名表来消歧：
     * <ul>
     *     <li>整段就是已知标签（{@code span}）→ 原样返回，不拆（避免 {@code <span>} 被拆成 {@code <s pan>}）；</li>
     *     <li>最长的已知标签名是它的真前缀（{@code htmllang} → {@code html}、{@code divclass} → {@code div}）
     *     → 返回该前缀，由调用方在后面补空格；</li>
     *     <li>没有已知标签名前缀（自定义元素 {@code my-card}）→ 返回空串，交给调用方整体搬运。</li>
     * </ul>
     *
     * @param tag   标签文本（以 {@code <} 开头，可能带 {@code /}）
     * @param start 标签名起始下标
     *
     * @return 标签名（需要在该名字后补空格），无法判断时返回空串
     */
    private static String splitKnownTagName(String tag, int start) {
        int nameEnd = start;
        while (nameEnd < tag.length() && isNameChar(tag.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == start) {
            return "";
        }
        // 去掉自闭合的 /：<br/> 里的标签名是 br
        int end = nameEnd;
        while (end > start && tag.charAt(end - 1) == '/') {
            end--;
        }
        String candidate = tag.substring(start, end);
        if (KNOWN_TAG_NAMES.contains(candidate.toLowerCase(Locale.ROOT))) {
            // 整段就是已知标签名：不拆
            return candidate;
        }
        // 找"最长的已知标签名前缀"：优先长标签名，避免 scriptsrc 被拆成 s + criptsrc
        for (int prefixEnd = candidate.length() - 1; prefixEnd > 0; prefixEnd--) {
            String prefix = candidate.substring(0, prefixEnd).toLowerCase(Locale.ROOT);
            if (KNOWN_TAG_NAMES.contains(prefix)) {
                return candidate.substring(0, prefixEnd);
            }
        }
        return "";
    }

    /**
     * 标签名/属性名允许的字符
     */
    private static boolean isNameChar(char value) {
        return Character.isLetterOrDigit(value) || value == '-' || value == '_' || value == ':';
    }

    /**
     * 修复 {@code <!DOCTYPEhtml>} 这类声明（含缺少空格的写法）
     */
    private static String repairDoctype(String html) {
        return Pattern.compile("<!\\s*DOCTYPE\\s*(?=[A-Za-z])", Pattern.CASE_INSENSITIVE)
                .matcher(html)
                .replaceAll("<!DOCTYPE ");
    }

    /**
     * 把一段文本按 Markdown 代码块切开，返回「语言标识 -> 内容」的有序映射
     * <p>
     * 相比逐语言写正则：
     * <ul>
     *     <li>只把真正位于代码块内的内容当作代码，避免"HTML 代码块里出现的 ```css 字样"串味；</li>
     *     <li>容忍未闭合的代码块（模型截断时匹配到文本结束）；</li>
     *     <li>同时兼容 3 个及以上反引号、以及 ~ 作为围栏</li>
     * </ul>
     * 同一种语言出现多次时只保留第一次（提示词已约束"每种语言只输出一个代码块"）。
     *
     * @param markdown 模型输出的完整 Markdown 文本
     *
     * @return 语言标识（小写）-> 代码内容
     */
    public static Map<String, String> extractCodeBlocks(String markdown) {
        Map<String, String> blocks = new LinkedHashMap<>();
        if (StrUtil.isBlank(markdown)) {
            return blocks;
        }
        // 先找所有"开围栏"，再往右寻找同类型的闭合围栏（长度不短于开围栏）。
        // 用 region 把搜索范围右移，因此 HTML 代码块里出现的 ```css 字样不会串到真正的 CSS 上。
        Matcher opener = Pattern.compile("(?m)^[ \\t]*(`{3,}|~{3,})[ \\t]*([A-Za-z0-9_+#.-]*)[^`\\n]*$")
                .matcher(markdown);
        int cursor = 0;
        while (cursor < markdown.length() && opener.find(cursor)) {
            String fence = opener.group(1);
            String label = opener.group(2) == null ? "" : opener.group(2).trim().toLowerCase();
            int contentStart = markdown.indexOf('\n', opener.end());
            if (contentStart < 0) {
                // 开围栏后没有换行：视为未闭合，内容为空
                break;
            }
            contentStart += 1;
            // 在同一类型的闭合围栏处结束；找不到就一直延伸到文本结束（模型截断）
            Matcher closer = Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(fence) + fence.charAt(0) + "*[ \\t]*$")
                    .matcher(markdown);
            closer.region(contentStart, markdown.length());
            int contentEnd = markdown.length();
            int nextCursor = markdown.length();
            if (closer.find()) {
                contentEnd = closer.start();
                nextCursor = closer.end();
            }
            if (label.matches("[a-zA-Z0-9_+#.-]+")) {
                blocks.putIfAbsent(label, stripTrailingNewline(removeFenceIndent(markdown.substring(contentStart, contentEnd))));
            }
            cursor = Math.max(nextCursor, contentStart);
        }
        return blocks;
    }

    /**
     * 去掉代码内容里因代码块缩进产生的公共前导空格（最多 3 个），保留相对缩进
     * <p>
     * 不做这一步的话，模型把代码块整体缩进时（例如段落里的列表嵌套），落盘的 HTML 每一行都会多出空格。
     *
     * @param content 代码块内容
     *
     * @return 去掉公共缩进后的内容
     */
    private static String removeFenceIndent(String content) {
        if (StrUtil.isEmpty(content)) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && spaces < 3 && line.charAt(spaces) == ' ') {
                spaces++;
            }
            common = Math.min(common, spaces);
        }
        if (common <= 0 || common == Integer.MAX_VALUE) {
            return content;
        }
        StringBuilder builder = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            builder.append(line.length() >= common ? line.substring(common) : line);
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * 去掉内容末尾多余的换行（代码块闭合前的空行不属于代码）
     */
    private static String stripTrailingNewline(String content) {
        int end = content.length();
        while (end > 0 && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
            end--;
        }
        return content.substring(0, end);
    }
}
