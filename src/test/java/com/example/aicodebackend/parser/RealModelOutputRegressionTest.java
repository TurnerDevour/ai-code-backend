package com.example.aicodebackend.parser;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实模型输出</b>做回归：{@code temp/probe-*.txt} 是探测脚本抓下来的原始流内容。
 * <p>
 * 这些文件记录了实测现象：模型会吞掉"标签名与属性之间的空格"（{@code <!DOCTYPEhtml>}、
 * {@code <linkrel="stylesheet"href="style.css">}），导致 CSS / JavaScript 引用失效，
 * 用户看到的就是"生成的网站没有样式、没有交互"。
 * <p>
 * 文件不存在时跳过（它们只是排查用的样本，不进版本库）。
 */
class RealModelOutputRegressionTest {

    private static final Path TEMP_DIR = Path.of(System.getProperty("user.dir"), "temp");
    /** 多文件模式：必须解析出 css / js 两个文件 */
    private static final String[] MULTI_FILE_PROBES = {"probe-round1.txt", "probe-round2.txt", "probe-mf-v4pro.txt"};

    @Test
    void htmlModeProbesShouldComeOutAsUsableHtml() throws IOException {
        // probe-html-qwen37plus.txt：qwen3.7-plus 实测样本，脚本里有 i < 60 与 (num, index) =>，
        // 曾经被标签修复当成"粘连标签"重排空格（createElement(' div ')），导致页面能看不能用
        for (String name : new String[]{"probe-html-flash.txt", "probe-html-qwen37plus.txt"}) {
            Path file = TEMP_DIR.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            HTMLCodeResult result = new HTMLCodeParser().parserCode(raw);
            String html = result.getHtmlCode();
            assertNotNull(html, name + " 应解析出 HTML");
            assertTrue(html.startsWith("<!DOCTYPE html>"), name + " 的 doctype 应被修复");
            assertFalse(GeneratedCodeRepair.hasGluedTags(html), name + " 不应残留粘连标签");
            // HTML 模式的 CSS / JavaScript 是内联的，修复后标签必须能正常闭合配对
            assertTrue(html.contains("<style>"), name + " 应保留内联样式块");
            assertTrue(html.contains("<script>"), name + " 应保留内联脚本块");
            assertTrue(html.contains("</style>") && html.contains("</script>"), name + " 内外联块应完整");
        }
    }

    @Test
    void multiFileProbesShouldBeParsedWithoutGluedTags() throws IOException {
        for (String name : MULTI_FILE_PROBES) {
            Path file = TEMP_DIR.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            MultiFileCodeResult result = new MultiFileCodeParser().parserCode(raw);
            String html = result.getHtmlCode();
            if (html == null) {
                continue;
            }
            // 核心断言：修复后不能再有粘连标签，否则 link / script 不会生效
            assertFalse(GeneratedCodeRepair.hasGluedTags(html),
                    name + " 的 HTML 修复后不应残留粘连标签: " + firstGlued(html));
        }
    }

    /**
     * 记录一个重要的实测事实：deepseek-flash 在多文件模式下<b>经常只输出 HTML 代码块</b>
     * （{@code index.html} 里却已经写好了 {@code <link href="style.css">} 与 {@code <script src="script.js">}）。
     * <p>
     * 解析层在这里只能如实返回"CSS / JS 缺失"，真正的补齐由
     * {@code AiCodeGeneratorFacade#completeMissingFiles} 负责（见 AiCodeGeneratorFacadeCompletionTest）。
     * 这个用例保证"缺失检测"没有被后续改动悄悄优化掉。
     */
    @Test
    void multiFileProbeWithoutCssBlocksShouldReportMissing() throws IOException {
        Path file = TEMP_DIR.resolve("probe-round1.txt");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        MultiFileCodeResult result = new MultiFileCodeParser().parserCode(raw);
        assertNotNull(result.getHtmlCode(), "即使模型只给 HTML，也要能解析出 index.html");
        assertTrue(result.getHtmlCode().contains("style.css"), "HTML 里应保留对 style.css 的引用");
        assertNull(result.getCssCode(), "模型没输出 css 代码块时，解析结果必须为空（由门面层补全）");
        assertNull(result.getJsCode(), "模型没输出 js 代码块时，解析结果必须为空（由门面层补全）");
    }

    /**
     * 找出第一处粘连片段，便于断言失败时定位
     */
    private static String firstGlued(String html) {
        for (String snippet : new String[]{"<!DOCTYPEh", "<htmll", "<metac", "<linkr", "<headerc", "<divc", "<scriptc"}) {
            int index = html.indexOf(snippet);
            if (index >= 0) {
                return snippet + " @ " + index;
            }
        }
        return "未定位到";
    }

    /**
     * 样本目录本身应可读（避免路径写错导致上面的用例静默跳过）
     */
    @Test
    void tempDirectoryShouldBeReadable() throws IOException {
        assertTrue(Files.isDirectory(TEMP_DIR), "样本目录应存在: " + TEMP_DIR);
        try (Stream<Path> files = Files.list(TEMP_DIR)) {
            assertNotNull(files);
        }
    }
}
