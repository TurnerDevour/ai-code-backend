package com.example.aicodebackend.saver;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;

/**
 * HTML 代码文件保存器模板类
 */
public class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HTMLCodeResult> {
    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    @Override
    protected void saveFiles(HTMLCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
    }

    @Override
    protected void validateInput(HTMLCodeResult result) {
        super.validateInput(result);

        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new IllegalArgumentException("HTML 代码不能为空");
        }
    }
}
