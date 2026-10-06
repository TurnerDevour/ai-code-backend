package com.example.aicodebackend.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型「结构化输出（JSON）」答案的提取测试
 * <p>
 * 用例取自真实输出：模型按 LangChain4j 为 POJO 返回类型追加的 JSON 格式要求回答，
 * 而且这一轮输出把代码里的行内空格全吞掉了（换行 \n 与缩进仍在）。
 */
class StructuredCodeAnswerTest {

    /** 真实形态：整轮丢空格的结构化 JSON 答案 */
    private static final String GLUED_JSON_ANSWER = """
            {
              "htmlCode": "<!DOCTYPEhtml>\\n<htmllang=\\"zh-CN\\">\\n<head>\\n  <linkrel=\\"stylesheet\\"href=\\"style.css\\">\\n</head>\\n<body>\\n  <divclass=\\"containerhero-inner\\">首页</div>\\n  <scriptsrc=\\"script.js\\"></script>\\n</body>\\n</html>",
              "cssCode": ":root{\\n  --shadow-sm:04px16pxrgba(15,27,45,.06);\\n}",
              "jsCode": "(function(){\\n'usestrict';\\nvartimer=null;\\n})();",
              "description": "本次输出为演示网站的完整三文件实现。"
            }
            """;

    @Test
    void shouldExtractAllFieldsFromGluedJsonAnswer() {
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(GLUED_JSON_ANSWER);

        assertFalse(answer.isEmpty());
        String html = answer.htmlCode();
        assertEquals("<!DOCTYPEhtml>\n<htmllang=\"zh-CN\">\n<head>\n  <linkrel=\"stylesheet\"href=\"style.css\">\n</head>\n"
                + "<body>\n  <divclass=\"containerhero-inner\">首页</div>\n  <scriptsrc=\"script.js\"></script>\n</body>\n</html>", html,
                "取值要完成反转义（\\n、\\\" 都要还原成真实字符）");
        assertEquals(":root{\n  --shadow-sm:04px16pxrgba(15,27,45,.06);\n}", answer.cssCode());
        assertEquals("(function(){\n'usestrict';\nvartimer=null;\n})();", answer.jsCode());
        assertEquals("本次输出为演示网站的完整三文件实现。", answer.description());
    }

    @Test
    void shouldExtractFieldsWrappedInJsonFenceWithProseAround() {
        String content = """
                这是为您生成的三个文件：
                ```json
                {
                  "htmlCode": "<!DOCTYPE html>\\n<html lang=\\"zh-CN\\"></html>",
                  "cssCode": "body { margin: 0; }",
                  "jsCode": "console.log(1)"
                }
                ```
                页面可以直接落盘使用。
                """;
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);

        assertEquals("<!DOCTYPE html>\n<html lang=\"zh-CN\"></html>", answer.htmlCode());
        assertEquals("body { margin: 0; }", answer.cssCode());
        assertEquals("console.log(1)", answer.jsCode());
        assertNull(answer.description(), "没有 description 字段时应返回 null");
    }

    @Test
    void shouldNotBeHijackedByFieldNameInsideValue() {
        // description 里提到了 "cssCode"，真正的 cssCode 字段在后面：不能被前面的字样抢走
        String content = "{\"htmlCode\":\"<html></html>\","
                + "\"description\":\"说明：cssCode 字段是样式，jsCode 字段是脚本\","
                + "\"cssCode\":\"body{color:red}\","
                + "\"jsCode\":\"console.log(1)\"}";
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);

        assertEquals("body{color:red}", answer.cssCode());
        assertEquals("console.log(1)", answer.jsCode());
    }

    @Test
    void shouldKeepPartialFieldsWhenOutputIsTruncated() {
        String content = "{\"htmlCode\":\"<html><body>被截断的页面";
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);

        assertEquals("<html><body>被截断的页面", answer.htmlCode(), "截断时也应尽量把已有内容取出来");
        assertNull(answer.cssCode());
    }

    @Test
    void shouldRecoverValueWithUnescapedQuotes() {
        // 模型没给取值里的引号转义（非法 JSON）：按字段扫描仍要拿到后续字段
        String content = "{\"htmlCode\":\"<div class=\"box\">x</div>\",\"cssCode\":\".box{color:red}\"}";
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);

        assertNotNull(answer.htmlCode());
        assertEquals(".box{color:red}", answer.cssCode(), "取值里的裸引号不应吞掉后面的字段");
    }

    @Test
    void shouldSupportMissingCommaBetweenFields() {
        String content = "{\"htmlCode\":\"<html></html>\"\n\"cssCode\":\"body{margin:0}\"}";
        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);

        assertEquals("<html></html>", answer.htmlCode());
        assertEquals("body{margin:0}", answer.cssCode());
    }

    @Test
    void shouldDecodeEscapesAndKeepUnknownOnes() {
        assertEquals("a\nb\tc\rd\"e\\f/g", StructuredCodeAnswer.unescape("a\\nb\\tc\\rd\\\"e\\\\f\\/g"));
        assertEquals("中文", StructuredCodeAnswer.unescape("\\u4e2d\\u6587"));
        assertEquals("\\d+", StructuredCodeAnswer.unescape("\\d+"), "未知转义要原样保留（正则里的 \\d 不能被吃掉）");
    }

    @Test
    void shouldReturnEmptyWhenThereIsNoStructuredField() {
        assertTrue(StructuredCodeAnswer.parse("```html\n<html></html>\n```").isEmpty());
        assertTrue(StructuredCodeAnswer.parse(null).isEmpty());
        assertTrue(StructuredCodeAnswer.parse("   ").isEmpty());
        // 只是普通文本里出现了字段名，没有 : "取值" 结构
        assertTrue(StructuredCodeAnswer.parse("请把 htmlCode 填好").isEmpty());
    }
}
