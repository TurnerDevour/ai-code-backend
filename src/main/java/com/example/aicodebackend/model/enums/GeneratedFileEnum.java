package com.example.aicodebackend.model.enums;

import lombok.Getter;

import java.util.Arrays;

/** 生成产物文件约定：文件名、Markdown 语言标记与代码块语言别名 */
@Getter
public enum GeneratedFileEnum {

    HTML("html", "index.html", "html", new String[]{"html", "htm", "xhtml"}),
    CSS("css", "style.css", "css", new String[]{"css", "scss", "sass", "less"}),
    JS("js", "script.js", "javascript", new String[]{"js", "javascript", "mjs", "cjs", "ts", "typescript"});

    /** 三文件映射里的键 */
    private final String key;

    /** 落盘文件名 */
    private final String fileName;

    /** Markdown 代码块语言标记 */
    private final String language;

    /** 模型可能使用的代码块语言别名 */
    private final String[] blockAliases;

    GeneratedFileEnum(String key, String fileName, String language, String[] blockAliases) {
        this.key = key;
        this.fileName = fileName;
        this.language = language;
        this.blockAliases = blockAliases;
    }

    /** 按文件名取枚举，未匹配时返回 null */
    public static GeneratedFileEnum fromFileName(String fileName) {
        return Arrays.stream(values())
                .filter(file -> file.fileName.equalsIgnoreCase(fileName))
                .findFirst()
                .orElse(null);
    }
}
