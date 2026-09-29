package com.example.aicodebackend.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
@Description("生成的多文件代码结果，包括HTML、CSS和JavaScript代码")
public class MultiFileCodeResult {

    @Description("HTML代码")
    private String htmlCode;

    @Description("CSS代码")
    private String cssCode;

    @Description("JavaScript代码")
    private String jsCode;

    @Description("生成多文件代码的描述")
    private String description;
}
