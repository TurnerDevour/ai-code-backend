package com.example.aicodebackend.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.model.HTMLCodeResult;
import com.example.aicodebackend.ai.model.MultiFileCodeResult;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 代码文件保存器，用于将生成的代码保存为文件。
 */
public class CodeFileSaver {

    // 文件保存目录路径System.getProperty("user.dir")
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/src/main/resources/temp/code_output";

    // 保存HTML代码到文件
    public static File saveHtmlCodeToFile(HTMLCodeResult htmlCodeResult) {
        String baseDirPath = buildUniqueDir(CodeGenTypeEnum.HTML.getValue());
        if (!writeToFile(baseDirPath, "index.html", htmlCodeResult.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成有效的 HTML 代码，无法保存文件");
        }
        return new File(baseDirPath);
    }

    // 保存多文件代码到文件
    public static File saveMultiFileCodeToFile(MultiFileCodeResult multiFileCodeResult) {
        String baseDirPath = buildUniqueDir(CodeGenTypeEnum.MULTI_FILE.getValue());
        int written = 0;
        written += writeToFile(baseDirPath, "index.html", multiFileCodeResult.getHtmlCode()) ? 1 : 0;
        written += writeToFile(baseDirPath, "style.css", multiFileCodeResult.getCssCode()) ? 1 : 0;
        written += writeToFile(baseDirPath, "script.js", multiFileCodeResult.getJsCode()) ? 1 : 0;
        if (written == 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成任何有效代码，无法保存文件");
        }
        return new File(baseDirPath);
    }

    // 构建唯一目录路径：temp/code_output/bizType_雪花ID
    private static String buildUniqueDir(String bizType) {
        String uniqueDirName = StrUtil.format("{}_{}", bizType, IdUtil.getSnowflakeNextIdStr());
        String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
        FileUtil.mkdir(dirPath);
        return dirPath;
    }

    // 写入单个文件内容到指定路径，内容为空时跳过并返回 false
    private static boolean writeToFile(String dirPath, String filename, String content) {
        if (StrUtil.isBlank(content)) {
            return false;
        }
        String filepath = dirPath + File.separator + filename;
        FileUtil.writeString(content, filepath, StandardCharsets.UTF_8);
        return true;
    }
}
