package com.example.aicodebackend.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 抽象类，定义了保存代码文件的模板方法
 */
public abstract class CodeFileSaverTemplate<T> {

    protected static final String FILE_SAVE_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    /**
     * 保存代码文件的模板方法
     *
     * @param result 输入结果
     *
     * @return 保存的目录
     */
    public final File saveCode(T result, Long appId) {
        // 1. 验证输入
        validateInput(result);
        // 2. 基于appId来构建唯一目录路径
        String baseDirPath = buildUniqueDir(appId);
        // 3. 保存代码文件
        saveFiles(result, baseDirPath);
        // 4. 返回保存的目录
        return new File(baseDirPath);
    }

    /**
     * 验证输入结果是否为空
     *
     * @param result 输入结果
     */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "输入结果不能为空");
        }
    }

    /**
     * 构建唯一目录路径：temp/code_output/{codeType}_{appId}
     *
     * @return 唯一目录路径
     */
    protected final String buildUniqueDir(Long appId) {

        if (appId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "appId不能为空");
        }

        String codeType = getCodeType().getValue();
        String uniqueDirName = StrUtil.format("{}_{}", codeType, appId);
        String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
        FileUtil.mkdir(dirPath);
        return dirPath;
    }

    /**
     * 写入单个文件内容到指定路径
     *
     * @param dirPath  文件保存目录路径
     * @param filename 文件名
     * @param content  文件内容
     */
    protected final void writeToFile(String dirPath, String filename, String content) {
        if (StrUtil.isNotBlank(content)) {
            String filepath = dirPath + File.separator + filename;
            FileUtil.writeString(content, filepath, StandardCharsets.UTF_8);
        }
    }

    /**
     * 获取代码生成类型，用于构建唯一目录路径
     *
     * @return 代码生成类型
     */
    protected abstract CodeGenTypeEnum getCodeType();

    /**
     * 保存文件的具体实现，由子类实现
     *
     * @param result      输入结果
     * @param baseDirPath 基础目录路径
     */
    protected abstract void saveFiles(T result, String baseDirPath);
}
