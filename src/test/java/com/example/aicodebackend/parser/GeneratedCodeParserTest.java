package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生成代码解析与修复的单元测试
 * <p>
 * 用例取自真实模型输出（deepseek-flash / deepseek-v4-pro 实测）：
 * 1) 标签名与属性之间的空格被吞掉（{@code <linkrel="stylesheet"href="style.css">}）；
 * 2) 多文件模式只输出 HTML 代码块，CSS / JavaScript 代码块整个缺失；
 * 3) 代码块围栏缺少换行 / 语言标记大小写不一致。
 */
class GeneratedCodeParserTest {

    /** 真实输出的开头片段：标签名与属性粘连 */
    private static final String GLUED_HTML = """
            这是一个极简风格的个人博客首页HTML文件。
            ```html
            <!DOCTYPEhtml>
            <htmllang="zh-CN">
            <head>
              <metacharset="UTF-8">
              <metaname="viewport"content="width=device-width,initial-scale=1.0">
              <linkrel="stylesheet"href="style.css">
              <title>林默·个人博客</title>
            </head>
            <body>
              <divclass="containerheader-inner">
                <ahref="#top"class="logo">林默</a>
                <scriptsrc="script.js"></script>
              </div>
            </body>
            </html>
            ```
            """;

    @Test
    void repairHtmlShouldSplitGluedTagNameAndAttributes() {
        String repaired = GeneratedCodeRepair.repairHtml(GLUED_HTML);
        assertTrue(repaired.contains("<!DOCTYPE html>"), "doctype 需要补空格: " + repaired);
        assertTrue(repaired.contains("<html lang=\"zh-CN\">"), "html 标签需要补空格");
        assertTrue(repaired.contains("<meta charset=\"UTF-8\">"), "meta 标签需要补空格");
        assertTrue(repaired.contains("<link rel=\"stylesheet\" href=\"style.css\">"), "link 的属性之间也要补空格");
        assertTrue(repaired.contains("<script src=\"script.js\"></script>"), "script 标签需要补空格");
        assertTrue(repaired.contains("<div class=\"containerheader-inner\">"),
                "div 标签需要补空格（class 取值内部的空格模型本来就漏了，不做猜测）");
        assertTrue(repaired.contains("<a href=\"#top\" class=\"logo\">"), "a 标签需要补空格");
        // 中文文本与内联内容不能被破坏
        assertTrue(repaired.contains("林默·个人博客"), "标题文本不应被改动");
    }

    @Test
    void repairHtmlShouldNotTouchValidHtml() {
        String valid = "<div class=\"box\"><span>文本</span><a href=\"/x\">链接</a></div>";
        assertEquals(valid, GeneratedCodeRepair.repairHtml(valid), "合法 HTML 不应被改写");
        assertFalse(GeneratedCodeRepair.hasGluedTags(valid));
        assertTrue(GeneratedCodeRepair.hasGluedTags(GLUED_HTML));
    }

    /**
     * 边界用例：单字母标签、自闭合标签、布尔属性、自定义元素都不能被改坏
     */
    @Test
    void repairHtmlShouldHandleEdgeCases() {
        // 单字母标签与属性粘连
        assertEquals("<p class=\"a\">x</p>", GeneratedCodeRepair.repairHtml("<pclass=\"a\">x</p>"));
        // 自闭合标签
        assertEquals("<br/>", GeneratedCodeRepair.repairHtml("<br/>"));
        // 布尔属性（没有 =）
        assertEquals("<input disabled type=\"text\">",
                GeneratedCodeRepair.repairHtml("<inputdisabled type=\"text\">"));
        // 自定义元素（未知标签名）：不猜测，原样保留标签名
        assertEquals("<my-card>", GeneratedCodeRepair.repairHtml("<my-card>"));
        // script 标签名不能被拆成 s + criptsrc
        assertEquals("<script src=\"script.js\"></script>",
                GeneratedCodeRepair.repairHtml("<scriptsrc=\"script.js\"></script>"));
        // 单引号属性值
        assertEquals("<div id='a'>x</div>", GeneratedCodeRepair.repairHtml("<divid='a'>x</div>"));
        // 无引号属性值
        assertEquals("<div id=a>x</div>", GeneratedCodeRepair.repairHtml("<divid=a>x</div>"));
        // 注释 / 声明不被改动
        assertEquals("<!DOCTYPE html>", GeneratedCodeRepair.repairHtml("<!DOCTYPEhtml>"));
    }

    @Test
    void htmlParserShouldRepairGluedTagsAndDropProse() {
        HTMLCodeResult result = new HTMLCodeParser().parserCode(GLUED_HTML);
        String html = result.getHtmlCode();
        assertNotNull(html);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "应以 doctype 开头，解释文字要被丢掉: " + html.substring(0, 30));
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"style.css\">"));
    }

    @Test
    void htmlParserShouldFallbackWhenNoFence() {
        String raw = "说明文字\n<!DOCTYPE html>\n<html><body><h1>你好</h1></body></html>";
        HTMLCodeResult result = new HTMLCodeParser().parserCode(raw);
        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().startsWith("<!DOCTYPE html>"));
    }

    @Test
    void extractCodeBlocksShouldHandleAliasesAndSpacing() {
        String markdown = """
                前言
                ```HTML
                <html></html>
                ```
                ``` CSS
                body { color: red; }
                ```
                ```JS
                console.log(1)
                ```
                """;
        Map<String, String> blocks = GeneratedCodeRepair.extractCodeBlocks(markdown);
        assertEquals("<html></html>", blocks.get("html"));
        assertEquals("body { color: red; }", blocks.get("css"));
        assertEquals("console.log(1)", blocks.get("js"));
    }

    @Test
    void extractCodeBlocksShouldTolerateUnclosedFence() {
        Map<String, String> blocks = GeneratedCodeRepair.extractCodeBlocks("```html\n<html></html>");
        assertEquals("<html></html>", blocks.get("html"));
    }

    @Test
    void multiFileParserShouldFillAllThreeFiles() {
        String markdown = """
                下面是完整的三个文件。
                ```html
                <!DOCTYPEhtml>
                <html lang="zh-CN"><head><linkrel="stylesheet"href="style.css"></head>
                <body><scriptsrc="script.js"></script></body></html>
                ```
                ```css
                body { margin: 0; }
                ```
                ```javascript
                console.log('ok')
                ```
                """;
        MultiFileCodeResult result = new MultiFileCodeParser().parserCode(markdown);
        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().contains("<link rel=\"stylesheet\" href=\"style.css\">"), "HTML 应被修复");
        assertEquals("body { margin: 0; }", result.getCssCode());
        assertEquals("console.log('ok')", result.getJsCode());
    }

    @Test
    void multiFileParserShouldReportMissingCssAndJs() {
        // 实测场景：模型只输出了 HTML 代码块
        String markdown = """
                这是为您生成的暗黑话题社区HTML代码。
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><link rel="stylesheet" href="style.css"></head>
                <body>内容<script src="script.js"></script></body>
                </html>
                ```
                页面包含导航、话题列表等模块。
                """;
        MultiFileCodeResult result = new MultiFileCodeParser().parserCode(markdown);
        assertNotNull(result.getHtmlCode(), "HTML 代码块应被识别");
        assertNull(result.getCssCode(), "CSS 缺失必须被如实反映（由门面层补全）");
        assertNull(result.getJsCode(), "JavaScript 缺失必须被如实反映（由门面层补全）");
    }

    /**
     * 模型有时会把示例代码里的围栏也写进 HTML 代码块，导致 scanner 认为 HTML 块"没有闭合"，
     * 后面的真正 CSS / JS 代码块被一起吞进 HTML 块。此时应在 HTML 块内部再次扫描，
     * 尽量把 CSS / JS 捞回来（这是"有损但可用"的兜底，远好于整站没有样式）。
     */
    @Test
    void multiFileParserShouldRecoverBlocksNestedInsideHtmlBlock() {
        String markdown = """
                ```html
                <!DOCTYPE html>
                <html><body><pre>示例：
                ```css
                .a { color: red }
                ```
                </pre></body></html>
                ```
                """;
        MultiFileCodeResult result = new MultiFileCodeParser().parserCode(markdown);
        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode(), "嵌套在 HTML 块里的 CSS 应被兜底捞回");
        assertTrue(result.getCssCode().contains(".a"), "捞回的应为示例中的 CSS: " + result.getCssCode());
    }

    /**
     * 嵌套围栏的边界情形（记录当前的有损行为，避免以后误以为它一定精确）：
     * 上例中闭围栏出现的时机让顶层 scanner 认为 HTML 块尚未结束，真正的 CSS 块被吞进 HTML 块，
     * 此时只能从 HTML 块内部捞回示例里的 CSS——可用但不是最优。
     * 这里断言"至少捞到了 CSS"，同时把它标记为已知损失。
     */
    @Test
    void multiFileParserShouldAtLeastReturnSomeCssWhenFencesAreAmbiguous() {
        String markdown = """
                ```html
                <!DOCTYPE html>
                <html><body><pre>示例：
                ```css
                .a { color: red }
                ```
                </pre></body></html>
                ```
                ```css
                .real { color: blue }
                ```
                """;
        MultiFileCodeResult result = new MultiFileCodeParser().parserCode(markdown);
        assertNotNull(result.getCssCode(), "有损情况下也要尽量给出 CSS");
    }
}
