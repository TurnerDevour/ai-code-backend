package com.example.aicodebackend.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
@Description("生成的HTML代码结果")
public class HTMLCodeResult {

    @Description("HTML代码")
    private String htmlCode;

    @Description("生成HTML代码的描述")
    private String description;
}
