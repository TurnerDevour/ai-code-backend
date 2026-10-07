package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签空格修复的「改动边界」测试
 * <p>
 * 背景（实测事故，qwen3.7-plus 生成的时钟页）：脚本里同时含有 {@code i < 60} 与 {@code (num, index) =>}，
 * 这两个符号恰好凑成一对 {@code <} … {@code >}，被标签扫描器当成了一个"标签"，
 * 于是按"属性名=属性值"重排了这段 JavaScript 的空格：
 * <pre>
 * 原始：const mark = document.createElement('div');
 * 落盘：const mark= document.createElement( ' div ' );
 * </pre>
 * {@code createElement(' div ')} 会抛 {@code InvalidCharacterError}，整段脚本中断——
 * 页面"能看但不能用"（时钟停在 00:00:00）。同一处伪标签还会被
 * {@link GeneratedCodeRepair#countGluedTags} 算成"粘连"，让 HTML 本来完好的文档白跑一次修复调用。
 * <p>
 * 这组用例锁住修复范围：只修真正的标记，注释 / {@code <script>} / {@code <style>} 的内容一个字符都不能动。
 */
class GeneratedCodeRepairTest {

    /**
     * 真实失败样本：外层 HTML 确实有标签粘连，脚本本身是好的（已按模型原始输出还原）
     */
    private static final String GLUED_HTML_WITH_CLOCK_SCRIPT = """
            <!DOCTYPE html>
            <htmllang="zh-CN">
            <head>
                <metacharset="UTF-8">
                <title>极简钟表</title>
            </head>
            <body>
                <divid="clock"></div>

                <script>
                    function initClock() {
                        const clock = document.getElementById('clock');

                        // 生成 60 个刻度
                        for (let i = 0; i < 60; i++) {
                            const mark = document.createElement('div');
                            mark.className = 'mark' + (i % 5 === 0 ? ' major' : '');
                            clock.appendChild(mark);
                        }

                        // 生成 12 个数字
                        const numbers = [12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
                        const radius = 42;

                        numbers.forEach((num, index) => {
                            const numberEl = document.createElement('div');
                            numberEl.textContent = num;
                        });
                    }
                </script>
            </body>
            </html>
            """;

    /**
     * 取出 {@code <script>…</script>} 这一段（含标签本身）
     *
     * @param html HTML 文本
     *
     * @return 脚本片段
     */
    private static String scriptBlock(String html) {
        int start = html.indexOf("<script>");
        int end = html.indexOf("</script>");
        assertTrue(start >= 0 && end > start, "样本应包含完整的 script 块");
        return html.substring(start, end);
    }

    /** 真实事故回归：HTML 上的粘连照旧修好，脚本一个字符都不动 */
    @Test
    void shouldRepairGluedTagsButLeaveScriptUntouched() {
        String scriptBefore = scriptBlock(GLUED_HTML_WITH_CLOCK_SCRIPT);

        String repaired = GeneratedCodeRepair.repairHtml(GLUED_HTML_WITH_CLOCK_SCRIPT);

        assertTrue(repaired.contains("<html lang=\"zh-CN\">"), "html 标签要补空格");
        assertTrue(repaired.contains("<meta charset=\"UTF-8\">"), "meta 标签要补空格");
        assertTrue(repaired.contains("<div id=\"clock\">"), "div 标签要补空格");
        assertEquals(scriptBefore, scriptBlock(repaired), "脚本内容必须原样保留");
        assertTrue(repaired.contains("const mark = document.createElement('div');"),
                "字符串字面量前后不能被插入空格：createElement(' div ') 会抛 InvalidCharacterError");
        assertTrue(repaired.contains("for (let i = 0; i < 60; i++)"), "小于号不能被当成标签起始");
        assertTrue(repaired.contains("numbers.forEach((num, index) => {"), "箭头函数不能被当成标签结束");
    }

    /** 脚本里的 {@code <} / {@code >} 不是粘连特征：不能凭空触发一次修复 */
    @Test
    void gluedDetectionShouldIgnoreScriptContent() {
        String html = """
                <div id="app"></div>
                <script>
                    for (let i = 0; i < 60; i++) {
                        items.forEach((item, index) => { render(item); });
                    }
                </script>
                """;

        assertFalse(GeneratedCodeRepair.hasGluedTags(html), "脚本里的 < / > 不能被算成粘连");
        assertEquals(0, GeneratedCodeRepair.countGluedTags(html));
        assertEquals(html, GeneratedCodeRepair.repairHtml(html), "没有粘连时不应改写任何字符");
    }

    /** 样式块里的内容同样不是标记（{@code content: "<divclass='a'>"} 是取值，不是标签） */
    @Test
    void shouldNotTouchStyleContent() {
        String html = """
                <div id="app"></div>
                <style>
                    .tip::before { content: "<divclass='a'>"; }
                    .icon { background: url("data:image/svg+xml,<svgxmlns='http://www.w3.org/2000/svg'/>"); }
                </style>
                """;

        assertFalse(GeneratedCodeRepair.hasGluedTags(html), "样式块里的伪标签不能被算成粘连");
        assertEquals(html, GeneratedCodeRepair.repairHtml(html), "样式内容必须原样保留");
    }

    /** 注释里的内容不是标记：注释常常用来写"示例标签"，不能顺手改掉 */
    @Test
    void shouldNotTouchCommentContent() {
        String html = "<div id=\"app\"></div>\n<!-- 参考：<divid=\"old\"> 这种写法是旧的 -->";

        assertFalse(GeneratedCodeRepair.hasGluedTags(html));
        assertEquals(html, GeneratedCodeRepair.repairHtml(html), "注释内容必须原样保留");
    }

    /** 注释里提到 {@code <script>} 时，不能把后面的正文一起"保护"掉（保护区的配对不对被带偏） */
    @Test
    void shouldStillRepairTagsAfterCommentMentioningScript() {
        String html = "<!-- 记得引 <script> 标签 -->\n<divid=\"app\"></div>";

        String repaired = GeneratedCodeRepair.repairHtml(html);

        assertTrue(repaired.startsWith("<!-- 记得引 <script> 标签 -->"), "注释应原样保留: " + repaired);
        assertTrue(repaired.contains("<div id=\"app\">"), "注释之后的粘连标签仍要修复: " + repaired);
    }

    /** 加了保护区之后，真正的粘连仍然要能被检出与修复（功能不能被"保护"掉） */
    @Test
    void shouldStillRepairGluedTagsOutsideProtectedRegions() {
        String html = "<divid=\"app\"></div>\n<script>const a = 1;</script>";

        assertEquals(1, GeneratedCodeRepair.countGluedTags(html));
        assertEquals("<div id=\"app\"></div>\n<script>const a = 1;</script>", GeneratedCodeRepair.repairHtml(html));
    }

    /** 解析层整条链路：HTML 模式解析出的脚本必须与模型原始输出一致 */
    @Test
    void htmlParserShouldKeepScriptIntact() {
        String reply = "这是一个极简钟表页面。\n```html\n" + GLUED_HTML_WITH_CLOCK_SCRIPT + "```\n";

        HTMLCodeResult result = new HTMLCodeParser().parserCode(reply);
        String html = result.getHtmlCode();

        assertNotNull(html);
        assertTrue(html.contains("<div id=\"clock\">"), "粘连标签仍要被修复");
        assertTrue(html.contains("const mark = document.createElement('div');"), "脚本不能被改写");
        assertTrue(html.contains("numbers.forEach((num, index) => {"), "箭头函数不能被改写");
        assertFalse(GeneratedCodeRepair.hasGluedTags(html), "修复后不应残留粘连标签");
    }
}
