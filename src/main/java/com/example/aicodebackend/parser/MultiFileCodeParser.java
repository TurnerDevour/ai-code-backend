package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** 多文件解析器：从代码块或结构化 JSON 中提取 html / css / js */
@Slf4j
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    @Override
    public MultiFileCodeResult parserCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        String content = StrUtil.nullToEmpty(codeContent);
        Map<String, String> blocks = GeneratedCodeRepair.extractCodeBlocks(content);

        String htmlCode = GeneratedCodeRepair.firstNonBlank(blocks, GeneratedCodeRepair.HTML_BLOCK_ALIASES);
        String cssCode = GeneratedCodeRepair.firstNonBlank(blocks, GeneratedCodeRepair.CSS_BLOCK_ALIASES);
        String jsCode = GeneratedCodeRepair.firstNonBlank(blocks, GeneratedCodeRepair.JS_BLOCK_ALIASES);

        StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);
        if (!answer.isEmpty()) {
            htmlCode = htmlCode == null ? answer.htmlCode() : htmlCode;
            cssCode = cssCode == null ? answer.cssCode() : cssCode;
            jsCode = jsCode == null ? answer.jsCode() : jsCode;
        }
        if (htmlCode == null && answer.isEmpty()) {
            htmlCode = GeneratedCodeRepair.htmlFromDocumentStart(content);
        }

        if (htmlCode != null && (cssCode == null || jsCode == null)) {
            Map<String, String> nested = GeneratedCodeRepair.extractCodeBlocks(htmlCode);
            if (cssCode == null) {
                cssCode = GeneratedCodeRepair.firstNonBlank(nested, GeneratedCodeRepair.CSS_BLOCK_ALIASES);
            }
            if (jsCode == null) {
                jsCode = GeneratedCodeRepair.firstNonBlank(nested, GeneratedCodeRepair.JS_BLOCK_ALIASES);
            }
        }

        if (htmlCode == null && cssCode == null && jsCode == null) {
            if (GeneratedCodeRepair.looksLikeHtmlDocument(content)) {
                log.warn("多文件解析：没有识别到任何代码块，将整段内容作为 HTML 兜底");
                htmlCode = content.trim();
            } else {
                log.warn("多文件解析：没有识别到任何代码块，也没有 HTML 特征，本次不产出 index.html");
            }
        }

        if (htmlCode != null) {
            String normalized = GeneratedCodeRepair.normalizeHtml(htmlCode);
            if (GeneratedCodeRepair.looksLikeHtmlDocument(normalized)) {
                result.setHtmlCode(normalized);
            } else {
                log.warn("多文件解析：HTML 代码块内容不像完整页面，跳过写入 index.html");
            }
        }
        if (StrUtil.isNotBlank(cssCode)) {
            result.setCssCode(cssCode.trim());
        }
        if (StrUtil.isNotBlank(jsCode)) {
            result.setJsCode(jsCode.trim());
        }
        if (result.getCssCode() == null || result.getJsCode() == null) {
            log.warn("多文件解析：本次输出缺少代码块（css={}, js={}），将由门面层尝试补全",
                    result.getCssCode() != null, result.getJsCode() != null);
        }
        return result;
    }
}
