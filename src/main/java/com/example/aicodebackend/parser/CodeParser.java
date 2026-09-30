package com.example.aicodebackend.parser;

/**
 * 代码解析器接口
 */
public interface CodeParser<T> {

    /**
     * 解析代码内容
     *
     * @param codeContent 原始代码内容
     *
     * @return 解析后的结果对象
     */
    T parserCode(String codeContent);
}
