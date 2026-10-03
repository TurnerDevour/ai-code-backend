package com.example.aicodebackend.saver;

import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;

import java.io.File;

/**
 * 代码保存执行器
 * 根据代码文件的类型，选择不同的保存策略，将代码文件保存到指定位置
 */
public class CodeFileSaverExecutor {

    private static final HtmlCodeFileSaverTemplate htmlCodeFileSaver = new HtmlCodeFileSaverTemplate();

    private static final MultiFileCodeFileSaverTemplate multiFileCodeFileSaver = new MultiFileCodeFileSaverTemplate();

    /**
     * 执行代码保存操作
     *
     * @param codeResult      代码生成结果对象，可以是 HTMLCodeResult 或 MultiFileCodeResult
     * @param codeGenTypeEnum 代码生成类型枚举，指定生成 HTML 代码或多文件代码
     *
     * @return 保存的文件
     */
    public static File executeSaver(Object codeResult, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码生成类型不能为空");
        }

        return switch (codeGenTypeEnum) {
            case HTML -> htmlCodeFileSaver.saveCode((HTMLCodeResult) codeResult,appId);
            case MULTI_FILE -> multiFileCodeFileSaver.saveCode((MultiFileCodeResult) codeResult,appId);
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无效的值: " + codeGenTypeEnum);
        };
    }
}
