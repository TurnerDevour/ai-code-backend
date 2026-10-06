package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型「结构化输出（JSON）」答案的容错提取
 * <p>
 * 背景（实测）：{@code AiCodeGeneratorService} 的 {@code generateHTMLCode} / {@code generateMultipleFileCode}
 * 返回的是 POJO，LangChain4j 会为此在提示词里追加「必须严格按以下 JSON 格式回答」的要求，于是模型经常输出
 * <pre>
 * {"htmlCode":"&lt;!DOCTYPE html&gt;...","cssCode":"...","jsCode":"...","description":"..."}
 * </pre>
 * 而不是系统提示词里要求的三个 Markdown 代码块；有时还会把它包在 {@code ```json} 围栏里、前后夹着解释文字。
 * <p>
 * 流式场景下（{@code Flux<String>}）拿到的就是这段原始文本，如果解析器只认 Markdown 围栏，
 * 就会走「整段内容当 HTML」的兜底，把 JSON 原文（连 {@code "cssCode"}、{@code "jsCode"} 字段名一起）
 * 写进 index.html——实测产物目录里出现过这种文件。
 * <p>
 * 这里做的是<b>按字段名扫描</b>而不是整体 JSON 反序列化，原因有三：
 * <ol>
 *     <li>模型有时不转义取值里的引号、或在末尾被截断，整体反序列化会直接失败，而按字段扫描仍能拿到大部分内容；</li>
 *     <li>JSON 语法在空格丢失时仍然合法（{@code {"a":"b"}}），所以「粘连」不影响扫描；</li>
 *     <li>不需要引入额外依赖，也不必猜模型把哪个字段放在了哪个位置。</li>
 * </ol>
 * 取到的是 JSON 字符串字面量，因此会做一次反转义（{@code \n} / {@code \"} / {@code \\} / {@code \\uXXXX} 等），
 * 保证拿到的是可以直接落盘的代码文本，而不是带反斜杠的转义串。
 */
public final class StructuredCodeAnswer {

    /**
     * 结构化输出的字段名，顺序与 {@code MultiFileCodeResult} 保持一致
     */
    private static final String[] FIELD_NAMES = {"htmlCode", "cssCode", "jsCode", "description"};

    /**
     * 字段名 -> 反转义后的取值
     */
    private final Map<String, String> fields;

    private StructuredCodeAnswer(Map<String, String> fields) {
        this.fields = fields;
    }

    /**
     * 从模型输出里提取结构化字段
     * <p>
     * 完全没有结构化字段（纯 Markdown 代码块输出）时返回空对象，调用方应回落到代码块解析。
     *
     * @param content 模型输出的原始文本
     *
     * @return 提取结果（可能为空）
     */
    public static StructuredCodeAnswer parse(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (StrUtil.isBlank(content)) {
            return new StructuredCodeAnswer(fields);
        }
        List<Candidate> candidates = collectCandidates(content);
        // 已消费到的位置：避免把取值内部出现的 "cssCode": 之类的字样当成真字段
        int consumedUntil = -1;
        for (Candidate candidate : candidates) {
            if (candidate.position() < consumedUntil || fields.containsKey(candidate.name())) {
                continue;
            }
            Value value = readValue(content, candidate);
            if (value == null) {
                continue;
            }
            fields.put(candidate.name(), value.text());
            consumedUntil = value.end();
        }
        return new StructuredCodeAnswer(fields);
    }

    /**
     * 是否没识别出任何结构化字段
     */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * @return index.html 内容，未输出时返回 null
     */
    public String htmlCode() {
        return fields.get("htmlCode");
    }

    /**
     * @return style.css 内容，未输出时返回 null
     */
    public String cssCode() {
        return fields.get("cssCode");
    }

    /**
     * @return script.js 内容，未输出时返回 null
     */
    public String jsCode() {
        return fields.get("jsCode");
    }

    /**
     * @return 生成说明，未输出时返回 null
     */
    public String description() {
        return fields.get("description");
    }

    /**
     * 收集所有 {@code "字段名"} 出现的位置（按位置排序）
     * <p>
     * 先把候选位置全部找出来再按位置处理，是为了让「取值内部出现的字段名」天然被 {@code consumedUntil} 跳过。
     */
    private static List<Candidate> collectCandidates(String content) {
        List<Candidate> candidates = new ArrayList<>();
        for (String name : FIELD_NAMES) {
            String needle = "\"" + name + "\"";
            int from = 0;
            while (true) {
                int position = content.indexOf(needle, from);
                if (position < 0) {
                    break;
                }
                candidates.add(new Candidate(position, name));
                from = position + needle.length();
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::position));
        return candidates;
    }

    /**
     * 读取 {@code "字段名" : "取值"} 里的取值
     *
     * @param text      模型输出
     * @param candidate 字段名位置
     *
     * @return 取值与结束下标，格式不符时返回 null
     */
    private static Value readValue(String text, Candidate candidate) {
        int index = skipWhitespace(text, candidate.position() + candidate.name().length() + 2);
        if (index >= text.length() || text.charAt(index) != ':') {
            return null;
        }
        index = skipWhitespace(text, index + 1);
        if (index >= text.length() || text.charAt(index) != '"') {
            return null;
        }
        int valueStart = index + 1;
        int scan = valueStart;
        while (scan < text.length()) {
            int quote = nextUnescapedQuote(text, scan);
            if (quote < 0) {
                // 输出被截断：把剩余内容当作取值，总比丢掉整个文件好
                return new Value(unescape(text.substring(valueStart)), text.length());
            }
            int after = skipWhitespace(text, quote + 1);
            if (isValueEnd(text, after)) {
                return new Value(unescape(text.substring(valueStart, quote)), quote + 1);
            }
            // 取值里出现了未转义的引号（模型没转义）：当作取值内容继续往后找
            scan = quote + 1;
        }
        return new Value(unescape(text.substring(valueStart)), text.length());
    }

    /**
     * 判断引号之后是否已经读到取值结尾
     * <p>
     * 正常 JSON 是 {@code ,} / {@code }}；缺少逗号的粘连输出是直接跟着下一个字段名。
     */
    private static boolean isValueEnd(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        char current = text.charAt(index);
        if (current == ',' || current == '}' || current == ']') {
            return true;
        }
        if (current != '"') {
            return false;
        }
        for (String name : FIELD_NAMES) {
            if (text.startsWith("\"" + name + "\"", index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找到下一个未被反斜杠转义的引号
     */
    private static int nextUnescapedQuote(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) != '"') {
                continue;
            }
            int backslashes = 0;
            for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * JSON 字符串字面量反转义
     * <p>
     * 未知转义（例如正则里的 {@code \\d}）原样保留，避免破坏代码内容。
     *
     * @param raw 字面量（不含首尾引号）
     *
     * @return 反转义后的文本
     */
    static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder builder = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (current != '\\' || index + 1 >= raw.length()) {
                builder.append(current);
                index++;
                continue;
            }
            char next = raw.charAt(index + 1);
            switch (next) {
                case 'n' -> {
                    builder.append('\n');
                    index += 2;
                }
                case 'r' -> {
                    builder.append('\r');
                    index += 2;
                }
                case 't' -> {
                    builder.append('\t');
                    index += 2;
                }
                case 'b' -> {
                    builder.append('\b');
                    index += 2;
                }
                case 'f' -> {
                    builder.append('\f');
                    index += 2;
                }
                case '"', '\\', '/' -> {
                    builder.append(next);
                    index += 2;
                }
                case 'u' -> {
                    if (index + 5 < raw.length()) {
                        String hex = raw.substring(index + 2, index + 6);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 6;
                        } catch (NumberFormatException e) {
                            builder.append(current);
                            index++;
                        }
                    } else {
                        builder.append(current);
                        index++;
                    }
                }
                default -> {
                    builder.append(current).append(next);
                    index += 2;
                }
            }
        }
        return builder.toString();
    }

    /**
     * 字段名候选位置
     */
    private record Candidate(int position, String name) {
    }

    /**
     * 字段取值与结束下标
     */
    private record Value(String text, int end) {
    }
}
