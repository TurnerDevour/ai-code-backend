package com.example.aicodebackend.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Component
public class FileModifyTool extends AbstractFileTool {

    @Tool("修改文件内容，用新内容替换指定的旧内容")
    public String modifyFile(@P("文件的相对路径") String relativeFilePath, @P("要替换的旧内容") String oldContent,
                             @P("替换后的新内容") String newContent, @ToolMemoryId Long appId) {
        try {
            Path path = resolveProjectPath(relativeFilePath, appId);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            String originalContent = Files.readString(path);
            if (!originalContent.contains(oldContent)) {
                return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath;
            }
            String modifiedContent = originalContent.replace(oldContent, newContent);
            if (originalContent.equals(modifiedContent)) {
                return "信息：替换后文件内容未发生变化 - " + relativeFilePath;
            }
            Files.writeString(path, modifiedContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("成功修改文件: {}", path.toAbsolutePath());
            return "文件修改成功: " + relativeFilePath;
        } catch (PathOutsideProjectException e) {
            return "错误：路径不在项目目录内 - " + relativeFilePath;
        } catch (Exception e) {
            String errorMessage = "修改文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "modifyFile";
    }

    @Override
    public String getDisplayName() {
        return "修改文件内容";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("""
                %s
                
                修改前内容:
                ```
                %s
                ```
                修改后内容:
                ```
                %s
                ```
                """, toolCallHeader(arguments.getStr("relativeFilePath")),
                arguments.getStr("oldContent"), arguments.getStr("newContent"));
    }
}
