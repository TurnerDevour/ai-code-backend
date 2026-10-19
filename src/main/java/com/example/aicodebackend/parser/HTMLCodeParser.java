package com.example.aicodebackend.parser;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.model.HTMLCodeResult;

/** HTML 单文件解析器：兼容 Markdown 代码块、结构化 JSON 与整段 HTML */
public class HTMLCodeParser implements CodeParser<HTMLCodeResult> {

    @Override
    public HTMLCodeResult parserCode(String codeContent) {
        HTMLCodeResult result = new HTMLCodeResult();
        String content = StrUtil.nullToEmpty(codeContent);
        String htmlCode = GeneratedCodeRepair.extractHtmlCode(content);
        if (StrUtil.isBlank(htmlCode)) {
            StructuredCodeAnswer answer = StructuredCodeAnswer.parse(content);
            htmlCode = answer.htmlCode();
            if (answer.isEmpty() && content.indexOf('<') >= 0) {
                String fromDocumentStart = GeneratedCodeRepair.htmlFromDocumentStart(content);
                htmlCode = fromDocumentStart == null ? content : fromDocumentStart;
            }
        }
        if (StrUtil.isNotBlank(htmlCode)) {
            result.setHtmlCode(GeneratedCodeRepair.normalizeHtml(htmlCode));
        }
        return result;
    }
}
