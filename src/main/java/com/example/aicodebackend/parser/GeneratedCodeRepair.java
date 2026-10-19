package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.model.enums.GeneratedFileEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成代码的容错修复与代码块扫描
 * 背景（实测结论）：即使系统提示词明确要求"输出三个代码块"，模型仍会输出被破坏的 HTML——
 * 标签名与属性之间的空格会被吞掉，例如……
 */
public final class GeneratedCodeRepair {

    /**
     * 整个标签：从 {@code <} 到最近的 {@code >}
     * 所有修补都只在单个标签内部进行，这样既不会碰到正文文本，也不会把
     * {@code charset="UTF-8"><meta name="viewport"} 这种"跨标签"的内容误判成粘连……
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^<>]{0,2000}?>", Pattern.DOTALL);

    /**
     * 不参与标签修复的区域：注释、{@code <script>}、{@code <style>}
     * 背景（实测事故）：这个修复只应该改"HTML 标记"，但脚本里天然存在 {@code <} 与 {@code >}。
     * qwen3.7-plus 生成的一个时钟页里……
     */
    private static final Pattern PROTECTED_REGION_PATTERN = Pattern.compile(
            "<!--.*?-->"
                    + "|<script\\b[^>]*>.*?</script\\s*>"
                    + "|<style\\b[^>]*>.*?</style\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 已知 HTML 标签名：用于消解"标签名与属性名粘连"的歧义（见 {@link #splitKnownTagName}）。
     * 必须包含常见的单字母标签（{@code a} / {@code b} / {@code i} / {@code p} / {@code s} / {@code u}），
     * 否则 {@code <ahref="x">} 这类无法修复；但也因为存在单字母标签……
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

    /** 三个文件的键名（解析结果、修复指令、日志统一用它） */
    public static final String FILE_HTML = GeneratedFileEnum.HTML.getKey();
    public static final String FILE_CSS = GeneratedFileEnum.CSS.getKey();
    public static final String FILE_JS = GeneratedFileEnum.JS.getKey();

    /** 代码块语言别名 */
    public static final String[] HTML_BLOCK_ALIASES = GeneratedFileEnum.HTML.getBlockAliases();
    public static final String[] CSS_BLOCK_ALIASES = GeneratedFileEnum.CSS.getBlockAliases();
    public static final String[] JS_BLOCK_ALIASES = GeneratedFileEnum.JS.getBlockAliases();

    /** 模型偶尔把 HTML 标成 text / xml */
    public static final String[] HTML_TEXT_BLOCK_ALIASES = {"text", "xml", "plaintext"};

    private GeneratedCodeRepair() {
    }

    /**
     * HTML 起始标记
     * {@code \s*} 而不是 {@code \s+}：模型丢空格时 doctype 会被粘成 {@code <!DOCTYPEhtml>}
     */
    private static final Pattern HTML_START_PATTERN =
            Pattern.compile("<!doctype\\s*html|<html[\\s>]", Pattern.CASE_INSENSITIVE);

    /** 判断文本是否像一个完整的 HTML 页面 */
    public static boolean looksLikeHtmlDocument(String text) {
        return StrUtil.isNotBlank(text) && HTML_START_PATTERN.matcher(text).find();
    }

    /** 从 HTML 起始标记处截取，找不到时返回 null */
    public static String htmlFromDocumentStart(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        Matcher matcher = HTML_START_PATTERN.matcher(content);
        return matcher.find() ? content.substring(matcher.start()) : null;
    }

    /** 提取 HTML：优先 html/htm/xhtml 代码块，其次 text/xml 代码块 */
    public static String extractHtmlCode(String content) {
        Map<String, String> blocks = extractCodeBlocks(content);
        String html = firstNonBlank(blocks, HTML_BLOCK_ALIASES);
        if (html != null) {
            return html;
        }
        for (String alias : HTML_TEXT_BLOCK_ALIASES) {
            String block = blocks.get(alias);
            if (block != null && !block.isBlank() && block.contains("<")) {
                return block;
            }
        }
        return null;
    }

    /** 去空白并修复粘连标签 */
    public static String normalizeHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return html;
        }
        String trimmed = html.trim();
        return hasGluedTags(trimmed) ? repairHtml(trimmed) : trimmed;
    }

    /**
     * 从模型输出里取三个文件的内容
     * 两种形态都支持，代码块优先（那是系统提示词要求的格式）：
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

    /** 按别名顺序取第一个非空代码块 */
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
     * 只在确有粘连特征时改动；文本内容（如 &lt;title&gt;简·博客&lt;/title&gt;）不会被影响。
     * 标签之间的空白也保持原样（那属于代码风格，改动风险大于收益）……
     */
    public static String repairHtml(String html) {
        if (StrUtil.isBlank(html)) {
            return html;
        }
        String repaired = repairDoctype(html);
        List<int[]> protectedRegions = findProtectedRegions(repaired);
        Matcher matcher = TAG_PATTERN.matcher(repaired);
        StringBuilder builder = new StringBuilder(repaired.length() + 32);
        int last = 0;
        while (matcher.find()) {
            // 落在注释 / script / style 里的"标签"不是标记（脚本里的 i < 60 … => 就是典型），
            // 原样保留：既不改写，也不算作粘连
            if (overlapsProtectedRegion(protectedRegions, matcher.start(), matcher.end())) {
                continue;
            }
            builder.append(repaired, last, matcher.start());
            builder.append(repairTag(matcher.group()));
            last = matcher.end();
        }
        builder.append(repaired, last, repaired.length());
        return builder.toString();
    }

    /** 找出所有"不参与标签修复"的区域（注释 / {@code <script>} / {@code <style>}） */
    private static List<int[]> findProtectedRegions(String html) {
        List<int[]> regions = new ArrayList<>();
        Matcher matcher = PROTECTED_REGION_PATTERN.matcher(html);
        while (matcher.find()) {
            regions.add(new int[]{matcher.start(), matcher.end()});
        }
        return regions;
    }

    /**
     * 判断某个区间是否与任一保护区重叠
     * 用"重叠"而不是"包含"：跨过边界的片段（正文里的 {@code <} 一路匹配到脚本内部）同样不能修，
     * 因为改动它就会动到保护区里的字符。
     */
    private static boolean overlapsProtectedRegion(List<int[]> regions, int start, int end) {
        for (int[] region : regions) {
            if (start < region[1] && region[0] < end) {
                return true;
            }
        }
        return false;
    }

    /** 判断 HTML 文本是否存在"空格被吞掉"的粘连特征（用于日志与回归验证） */
    public static boolean hasGluedTags(String html) {
        return countGluedTags(html) > 0;
    }

    /**
     * 统计存在"标签名/属性粘连"的标签数量
     * 供 {@link CodeDamageDetector} 打分与门面层日志使用：数量比布尔值更能说明损坏范围。
     * 注释与 script / style 区域内的内容不参与统计：那里出现的 {@code <} / {@code >} 是代码本身……
     */
    public static int countGluedTags(String html) {
        if (StrUtil.isBlank(html)) {
            return 0;
        }
        List<int[]> protectedRegions = findProtectedRegions(html);
        int glued = 0;
        Matcher matcher = TAG_PATTERN.matcher(html);
        while (matcher.find()) {
            if (overlapsProtectedRegion(protectedRegions, matcher.start(), matcher.end())) {
                continue;
            }
            String tag = matcher.group();
            if (!repairTag(tag).equals(tag)) {
                glued++;
            }
        }
        return glued;
    }

    /**
     * 修复单个标签内部的空格丢失
     * 逐字符扫描：先跳过 {@code <} / {@code </} / 标签名，然后按"属性名 [= 值]"逐个解析属性，
     * 凡是属性名或属性值紧贴在上一段内容之后（中间没有任何空白）就补一个空格……
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

    /** 判断下标位置上的内容是否与前一个字符"紧贴"（中间没有任何空白或标签起始符） */
    private static boolean needsSeparator(String tag, int index) {
        if (index <= 0) {
            return false;
        }
        char previous = tag.charAt(index - 1);
        return previous != '<' && !Character.isWhitespace(previous);
    }

    /**
     * 从标签起始位置解析标签名：优先取"最长的已知标签名前缀"
     * 为什么要按已知标签名匹配：{@code <htmllang="zh-CN">} 里的 {@code htmllang} 在词法上就是一个
     * 合法标签名，无法用通用规则判断该不该拆。这里用 HTML 标签名表来消歧……
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

    /** 标签名/属性名允许的字符 */
    private static boolean isNameChar(char value) {
        return Character.isLetterOrDigit(value) || value == '-' || value == '_' || value == ':';
    }

    /** 修复 {@code <!DOCTYPEhtml>} 这类声明（含缺少空格的写法） */
    private static String repairDoctype(String html) {
        return Pattern.compile("<!\\s*DOCTYPE\\s*(?=[A-Za-z])", Pattern.CASE_INSENSITIVE)
                .matcher(html)
                .replaceAll("<!DOCTYPE ");
    }

    /**
     * 把一段文本按 Markdown 代码块切开，返回「语言标识 -> 内容」的有序映射
     * 相比逐语言写正则：
     * 同一种语言出现多次时只保留第一次（提示词已约束"每种语言只输出一个代码块"）。
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
     * 不做这一步的话，模型把代码块整体缩进时（例如段落里的列表嵌套），落盘的 HTML 每一行都会多出空格。
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

    /** 去掉内容末尾多余的换行（代码块闭合前的空行不属于代码） */
    private static String stripTrailingNewline(String content) {
        int end = content.length();
        while (end > 0 && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
            end--;
        }
        return content.substring(0, end);
    }
}
