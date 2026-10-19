package com.example.aicodebackend.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.utils.AppPathUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** 抽象类，定义了保存代码文件的模板方法 */
public abstract class CodeFileSaverTemplate<T> {

    protected static final String FILE_SAVE_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    /** 保存代码文件的模板方法 */
    public final File saveCode(T result, Long appId) {

        validateInput(result);

        String baseDirPath = buildUniqueDir(appId);

        saveFiles(result, baseDirPath);

        return new File(baseDirPath);
    }

    /** 验证输入结果是否为空 */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "输入结果不能为空");
        }
    }

    /** 构建唯一目录路径：temp/code_output/{codeType}_{appId} */
    protected final String buildUniqueDir(Long appId) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "appId不能为空");
        }
        String dirPath = AppPathUtils.projectDirPath(getCodeType().getValue(), appId);
        FileUtil.mkdir(dirPath);
        return dirPath;
    }

    /** 写入单个文件内容到指定路径 */
    protected final void writeToFile(String dirPath, String filename, String content) {
        if (StrUtil.isNotBlank(content)) {
            String filepath = dirPath + File.separator + filename;
            FileUtil.writeString(content, filepath, StandardCharsets.UTF_8);
        }
    }

    /** 获取代码生成类型，用于构建唯一目录路径 */
    protected abstract CodeGenTypeEnum getCodeType();

    /** 保存文件的具体实现，由子类实现 */
    protected abstract void saveFiles(T result, String baseDirPath);
}
