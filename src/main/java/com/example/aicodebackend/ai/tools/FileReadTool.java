package com.example.aicodebackend.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class FileReadTool extends AbstractFileTool {

    @Tool("读取指定路径的文件内容")
    public String readFile(@P("文件的相对路径") String relativeFilePath, @ToolMemoryId Long appId) {
        try {
            Path path = resolveProjectPath(relativeFilePath, appId);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            return Files.readString(path);
        } catch (PathOutsideProjectException e) {
            return "错误：路径不在项目目录内 - " + relativeFilePath;
        } catch (Exception e) {
            String errorMessage = "读取文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件内容";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return toolCallHeader(arguments.getStr("relativeFilePath"));
    }
}
