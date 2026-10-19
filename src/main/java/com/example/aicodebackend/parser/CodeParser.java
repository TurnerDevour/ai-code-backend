package com.example.aicodebackend.parser;

/** 代码解析器接口 */
public interface CodeParser<T> {

    /** 解析代码内容 */
    T parserCode(String codeContent);
}
