package com.example.aicodebackend.ai.tools;

import com.example.aicodebackend.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;


@Slf4j
public class FileWriteTool {

    /**
     * 写入文件到指定路径
     *
     * @param relativePath 文件的相对路径
     * @param content      文件内容
     *
     * @return 写入结果
     */
    @Tool("写入文件到指定路径")
    public String writeToFile(@P("文件的相对路径") String relativePath, @P("文件内容") String content, @ToolMemoryId Long appId) {
        try {
            Path path = Paths.get(relativePath);
            if (!path.isAbsolute()) {
                String projectDirname = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirname);
                path = projectRoot.resolve(relativePath);
            }

            // 创建父目录(如果不存在)
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("文件写入成功，文件路径：{}", path.toAbsolutePath());
            return "文件写入成功，文件路径：" + relativePath;
        } catch (IOException e) {
            String errorMessage = String.format("写入文件失败，文件路径：%s，错误信息：%s", relativePath, e.getMessage());
            log.error(errorMessage, e);
            return errorMessage;
        }
    }
}
