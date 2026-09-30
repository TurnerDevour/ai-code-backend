package com.example.aicodebackend.parser;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;

/**
 * CodeParserExecutor 是一个执行器类，用于根据代码生成类型解析代码内容。
 * 该类提供了统一的接口，封装了不同类型代码解析的逻辑，使客户端可以更方便地使用代码解析功能。
 */
public class CodeParserExecutor {

    private static final HTMLCodeParser htmlCodeParser = new HTMLCodeParser();
    private static final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    /**
     * 根据给定的代码内容和代码生成类型解析代码。
     *
     * @param CodeContent     需要解析的代码内容。
     * @param codeGenTypeEnum 代码生成类型枚举，指定解析 HTML 代码或多文件代码。
     *
     * @return 解析后的代码对象。
     *
     * @throws BusinessException 如果 codeGenTypeEnum 为 null，则抛出参数错误异常。
     */
    public static Object executorParser(String CodeContent, CodeGenTypeEnum codeGenTypeEnum) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "codeGenTypeEnum 不可以为空");
        }
        return switch (codeGenTypeEnum) {
            case HTML -> htmlCodeParser.parserCode(CodeContent);
            case MULTI_FILE -> multiFileCodeParser.parserCode(CodeContent);
        };
    }
}
