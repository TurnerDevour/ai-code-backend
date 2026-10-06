package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成代码「空格丢失」损伤检测
 * <p>
 * 背景（实测）：模型偶尔会在输出时吞掉代码里的所有行内空格——换行与缩进都还在，但关键字与标识符、
 * 标签与属性、属性值与单位之间全部粘连：
 * <pre>
 * &lt;!DOCTYPEhtml&gt;            padding:024px            varheader=null;
 * &lt;metacharset="UTF-8"&gt;     --shadow:04px16pxrgba(…)  functionsetYear(){
 * </pre>
 * 这种代码落盘后 HTML 里 {@code link} / {@code script} 不生效、CSS 声明整体失效、JavaScript 直接语法错误，
 * 用户看到的就是「没有样式、没有交互」。
 * <p>
 * 检测策略（宁可漏报也不误报，误报只会多花一次修复调用，漏报会让坏代码落盘）：
 * <ul>
 *     <li>HTML：标签名/属性粘连（复用 {@link GeneratedCodeRepair#hasGluedTags}）；
 *     以及 class 取值里"两个类名被粘成一个"（{@code class="containerhero-inner"}）——
 *     后者单看 HTML 完全合法，需要拿 CSS 里的类选择器当字典才能验出来，
 *     而它正是"标签都修好了、页面却仍然没有样式"的原因；</li>
 *     <li>CSS：数字+单位粘连、函数名与前一个 token 粘连属于强信号，一处即可判定；
 *     值关键字（solid / auto / flex …）粘连属于弱信号，需要多处，或与 HTML 同时受损；</li>
 *     <li>JavaScript：{@code function} / {@code return} / {@code typeof} / {@code 'use strict'} 这类
 *     极少作为标识符前缀的关键字粘连是强信号；{@code var} / {@code new} / {@code class} 这类
 *     可能是正常标识符前缀（variable / newValue / classList）的算弱信号，只在"同一轮里 HTML / CSS
 *     也受损"时才判定，避免误伤正常代码。</li>
 * </ul>
 * 交叉佐证的意义：这种丢空格是「整次回复级别」的现象，实测同一次回复里 HTML / CSS / JavaScript
 * 会一起受损，因此某个文件只有弱信号时，参考另外两个文件是否也受损可以显著降低误判。
 */
public final class CodeDamageDetector {

    /**
     * CSS 强信号：数字（含小数）紧跟单位后又直接接上别的 token
     * <p>
     * {@code 04px16pxrgba(…)}、{@code 1fr1fr}、{@code 100%50%}；正常书写的 CSS 不会出现这种形态。
     */
    private static final Pattern CSS_NUMBER_UNIT_GLUE = Pattern.compile(
            "(?<![\\w.#-])\\d+(?:\\.\\d+)?(?:px|rem|em|vh|vw|vmin|vmax|ch|ex|pt|pc|cm|mm|deg|rad|turn|ms|s|fr|%)(?=[A-Za-z0-9#.-])");

    /**
     * CSS 强信号：数字以 {@code 0} 开头紧跟另一位数字（{@code padding:024px}、{@code box-shadow:08px22px…}）
     * <p>
     * 正常的 CSS 数值不会出现「0 后面紧跟数字」的写法（{@code 0.5} 中间是小数点），
     * 这种形态只可能来自 {@code 0 24px} 被吞掉空格。前面排除 {@code #} 是为了不误伤 {@code #06b6d4} 这类颜色值。
     */
    private static final Pattern CSS_LEADING_ZERO_GLUE = Pattern.compile("(?<![\\w.#-])0\\d");

    /**
     * CSS 强信号：函数名与前面的 token 粘连（{@code transformvar(--ease)}、{@code 22pxrgba(…)}）
     * <p>
     * 前面的 token 必须是字母/数字/{@code %}/{@code )}：正常写法里 {@code :} 或空格之后是函数名（如 {@code background:linear-gradient(…)}），
     * 因此这条规则不会命中正常样式。
     */
    private static final Pattern CSS_FUNCTION_GLUE = Pattern.compile(
            "[A-Za-z0-9%)](?:var|calc|rgba|rgb|hsla|hsl|translateX|translateY|translateZ|translate|scaleX|scaleY|scale|rotate|skewX|skewY|skew|clamp|minmax|repeat|linear-gradient|radial-gradient|conic-gradient|cubic-bezier|steps|drop-shadow)\\(");

    /**
     * CSS 弱信号：值关键字与前面的 token 粘连（{@code 1.5pxsolid}、{@code 0auto}）
     */
    private static final Pattern CSS_KEYWORD_GLUE = Pattern.compile(
            "[0-9a-z%)](?:solid|dashed|dotted|double|auto|hidden|visible|transparent|cover|contain|nowrap|pointer|relative|absolute|fixed|sticky|grid|flex|block|inline|center|middle|baseline|uppercase|bold|italic|scroll|smooth|infinite|forwards|border-box|content-box)(?![a-z-])");

    /**
     * JavaScript 强信号：几乎不可能作为标识符前缀的关键字被粘连
     * <p>
     * 前面不能是标识符字符、{@code $} 或 {@code .}（避免把 {@code .forEach} 当成 {@code for}）。
     */
    private static final Pattern JS_STRONG_KEYWORD_GLUE = Pattern.compile(
            "(?<![\\w$.])(?:function|return|typeof|instanceof|delete|void|throw|yield|await|switch|while|catch|finally|break|continue)(?=[A-Za-z_$])");

    /**
     * JavaScript 强信号：{@code else if} 被粘成 {@code elseif}
     */
    private static final Pattern JS_ELSE_IF_GLUE = Pattern.compile("(?<![\\w$.])else(?=if)");

    /**
     * JavaScript 强信号：严格模式指令被粘成 {@code 'usestrict'}
     */
    private static final Pattern JS_USE_STRICT_GLUE = Pattern.compile("['\"]usestrict['\"]");

    /**
     * JavaScript 弱信号：可能是标识符前缀的关键字被粘连（{@code varheader}、{@code newDate}、{@code defaultValue}）
     */
    private static final Pattern JS_WEAK_KEYWORD_GLUE = Pattern.compile(
            "(?<![\\w$.])(?:var|let|const|new|class|case|default|import|export|extends|async)(?=[A-Za-z_$])");

    /** CSS 强信号一处即可判定 */
    private static final int CSS_STRONG_THRESHOLD = 1;
    /** CSS 弱信号单独判定需要的数量 */
    private static final int CSS_WEAK_THRESHOLD = 3;
    /** JavaScript 强信号的分值门槛（强信号每处 3 分） */
    private static final int JS_STRONG_SCORE_THRESHOLD = 3;

    private CodeDamageDetector() {
    }

    /**
     * 一次生成结果的损伤情况
     *
     * @param html       index.html 是否受损
     * @param css        style.css 是否受损
     * @param js         script.js 是否受损
     * @param htmlScore  HTML 损伤分值（0 表示干净）
     * @param cssScore   CSS 损伤分值（0 表示干净）
     * @param jsScore    JavaScript 损伤分值（0 表示干净）
     */
    public record Damage(boolean html, boolean css, boolean js, int htmlScore, int cssScore, int jsScore) {

        /**
         * @return 是否有任意文件受损
         */
        public boolean any() {
            return html || css || js;
        }
    }

    /**
     * 检测三个文件是否丢失了空格
     *
     * @param html index.html 内容（可为 null）
     * @param css  style.css 内容（可为 null，同时作为 HTML 类名的字典）
     * @param js   script.js 内容（可为 null）
     *
     * @return 损伤情况
     */
    public static Damage detect(String html, String css, String js) {
        int htmlScore = scoreHtml(html, css);
        boolean htmlDamaged = htmlScore > 0;

        int cssStrong = count(CSS_NUMBER_UNIT_GLUE, css) + count(CSS_LEADING_ZERO_GLUE, css)
                + count(CSS_FUNCTION_GLUE, css);
        int cssWeak = count(CSS_KEYWORD_GLUE, css);
        int cssScore = cssStrong * 3 + cssWeak;
        boolean cssDamaged = cssStrong >= CSS_STRONG_THRESHOLD
                || cssWeak >= CSS_WEAK_THRESHOLD
                || (cssWeak >= 1 && htmlDamaged);

        int jsStrong = (count(JS_STRONG_KEYWORD_GLUE, js) + count(JS_ELSE_IF_GLUE, js)
                + count(JS_USE_STRICT_GLUE, js)) * 3;
        int jsWeak = count(JS_WEAK_KEYWORD_GLUE, js);
        int jsScore = jsStrong + jsWeak;
        boolean crossDamaged = htmlDamaged || cssDamaged;
        // 弱信号单独出现时不判定受损：newValue / newIndex / classList 这类命名在正常代码里太常见，
        // 只有"同一轮里别的文件也粘连"时才认为它是丢空格
        boolean jsDamaged = jsStrong >= JS_STRONG_SCORE_THRESHOLD
                || (jsWeak >= 1 && crossDamaged);

        return new Damage(htmlDamaged, cssDamaged, jsDamaged, htmlScore, cssScore, jsScore);
    }

    /**
     * HTML 损伤分值：会被 {@link GeneratedCodeRepair} 判定为「标签粘连」的标签数量
     *
     * @param html HTML 文本
     *
     * @return 粘连标签数量（0 表示干净）
     */
    public static int scoreHtml(String html) {
        return GeneratedCodeRepair.countGluedTags(html) * 3;
    }

    /**
     * HTML 损伤分值（含类名粘连）：标签粘连 + class 取值里"两个类名被粘成一个"
     *
     * @param html HTML 文本
     * @param css  CSS 文本（作为类名字典，可为 null）
     *
     * @return 损伤分值（0 表示干净）
     */
    public static int scoreHtml(String html, String css) {
        return scoreHtml(html) + countMergedClassValues(html, css) * 3;
    }

    /**
     * 统计 HTML 里"两个类名被粘成一个"的 class 取值数量
     * <p>
     * 丢空格时 {@code class="container hero-inner"} 会变成 {@code class="containerhero-inner"}。
     * 这种损坏<b>单看 HTML 完全合法</b>（标签修复也救不了），但样式选择器再也匹配不上，
     * 页面就"没有样式"。这里用 CSS 里的类选择器当字典：如果某个 class 取值能被<b>完整地</b>切成
     * 两个及以上 CSS 里定义过的类名，那它一定是被粘起来的。
     * <p>
     * 注意不能因为"这个取值本身也在 CSS 里出现过"就跳过判断：整轮丢空格时 CSS 选择器同样会被粘
     * （{@code .hero-metrics li} 变成 {@code .hero-metricsli}），但 {@code .hero-metrics}、{@code .reveal}
     * 这些独立选择器一般还在，切分仍然成立。
     *
     * @param html HTML 文本
     * @param css  CSS 文本
     *
     * @return 被粘起来的 class 取值数量
     */
    public static int countMergedClassValues(String html, String css) {
        if (StrUtil.isBlank(html)) {
            return 0;
        }
        Set<String> knownClassNames = collectCssClassNames(css);
        if (knownClassNames.size() < 2) {
            return 0;
        }
        int merged = 0;
        for (String token : collectHtmlClassTokens(html)) {
            if (canSplitIntoClassNames(token, knownClassNames)) {
                merged++;
            }
        }
        return merged;
    }

    /**
     * 取出 HTML 里 {@code <style>} 块的内容（HTML 单文件模式的类名字典）
     *
     * @param html HTML 文本
     *
     * @return 内联样式内容，没有 {@code <style>} 块时返回空串
     */
    public static String extractInlineStyles(String html) {
        if (StrUtil.isBlank(html)) {
            return "";
        }
        StringBuilder styles = new StringBuilder();
        Matcher matcher = Pattern.compile("<style[^>]*>(.*?)</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(html);
        while (matcher.find()) {
            styles.append(matcher.group(1)).append('\n');
        }
        return styles.toString();
    }

    /**
     * 收集 CSS 里的类选择器名（{@code .card} -> {@code card}）
     */
    private static Set<String> collectCssClassNames(String css) {
        Set<String> names = new HashSet<>();
        if (StrUtil.isBlank(css)) {
            return names;
        }
        // 点号前面必须不是标识符字符，排除 url(x.png) / 1.5s / .5 这类情况
        Matcher matcher = Pattern.compile("(?:^|[^\\w-])\\.(-?[A-Za-z_][\\w-]*)").matcher(css);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 收集 HTML 里 class 属性的各个取值（{@code class="a b"} -> a, b）
     */
    private static Set<String> collectHtmlClassTokens(String html) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = Pattern.compile("class\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (matcher.find()) {
            for (String token : matcher.group(1).trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    /**
     * 判断一个 class 取值能否被完整切成两个及以上已知类名（动态规划切分）
     */
    private static boolean canSplitIntoClassNames(String token, Set<String> knownClassNames) {
        if (token.length() < 4) {
            return false;
        }
        boolean[] reachable = new boolean[token.length() + 1];
        int[] parts = new int[token.length() + 1];
        reachable[0] = true;
        for (int start = 0; start < token.length(); start++) {
            if (!reachable[start]) {
                continue;
            }
            // 每个片段至少 2 个字符：避免把 card 之类的短名切碎造成误判
            for (int end = start + 2; end <= token.length(); end++) {
                if (knownClassNames.contains(token.substring(start, end))) {
                    reachable[end] = true;
                    parts[end] = Math.max(parts[end], parts[start] + 1);
                }
            }
        }
        return reachable[token.length()] && parts[token.length()] >= 2;
    }

    /**
     * CSS 损伤分值：强信号 3 分/处，弱信号 1 分/处
     *
     * @param css CSS 文本
     *
     * @return 分值（0 表示干净）
     */
    public static int scoreCss(String css) {
        return (count(CSS_NUMBER_UNIT_GLUE, css) + count(CSS_LEADING_ZERO_GLUE, css)
                + count(CSS_FUNCTION_GLUE, css)) * 3
                + count(CSS_KEYWORD_GLUE, css);
    }

    /**
     * JavaScript 损伤分值：强信号 3 分/处，弱信号 1 分/处
     *
     * @param js JavaScript 文本
     *
     * @return 分值（0 表示干净）
     */
    public static int scoreJs(String js) {
        return (count(JS_STRONG_KEYWORD_GLUE, js) + count(JS_ELSE_IF_GLUE, js)
                + count(JS_USE_STRICT_GLUE, js)) * 3
                + count(JS_WEAK_KEYWORD_GLUE, js);
    }

    /**
     * 统计正则在文本里的命中次数
     */
    private static int count(Pattern pattern, String text) {
        if (StrUtil.isEmpty(text)) {
            return 0;
        }
        int hits = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }
}
