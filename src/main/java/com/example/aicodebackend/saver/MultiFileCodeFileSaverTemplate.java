package com.example.aicodebackend.saver;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.GeneratedFileEnum;

public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, GeneratedFileEnum.HTML.getFileName(), result.getHtmlCode());
        writeToFile(baseDirPath, GeneratedFileEnum.CSS.getFileName(), result.getCssCode());
        writeToFile(baseDirPath, GeneratedFileEnum.JS.getFileName(), result.getJsCode());
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码不能为空");
        }
    }
}
