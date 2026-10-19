package com.example.aicodebackend.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.example.aicodebackend.utils.ProjectFileFilters;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class FileDirReadTool extends AbstractFileTool {

    @Tool("读取目录结构，获取指定目录下的所有文件和子目录信息")
    public String readDir(@P("目录的相对路径，为空则读取整个项目结构") String relativeDirPath,
                          @ToolMemoryId Long appId) {
        try {
            Path path = StrUtil.isBlank(relativeDirPath)
                    ? projectRoot(appId)
                    : resolveProjectPath(relativeDirPath, appId);
            File targetDir = path.toFile();
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            Path rootPath = targetDir.toPath();
            List<File> allFiles = FileUtil.loopFiles(targetDir,
                    file -> !ProjectFileFilters.shouldIgnore(rootPath.relativize(file.toPath())));
            StringBuilder structure = new StringBuilder("项目目录结构:\n");
            allFiles.stream()
                    .sorted(Comparator
                            .comparingInt((File file) -> rootPath.relativize(file.toPath()).getNameCount())
                            .thenComparing(File::getPath))
                    .forEach(file -> {
                        Path relative = rootPath.relativize(file.toPath());
                        structure.append("  ".repeat(relative.getNameCount() - 1))
                                .append(relative.getFileName())
                                .append('\n');
                    });
            return structure.toString();
        } catch (PathOutsideProjectException e) {
            return "错误：路径不在项目目录内 - " + relativeDirPath;
        } catch (Exception e) {
            String errorMessage = "读取目录结构失败: " + relativeDirPath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "readDir";
    }

    @Override
    public String getDisplayName() {
        return "读取目录结构";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeDirPath = arguments.getStr("relativeDirPath");
        return toolCallHeader(StrUtil.blankToDefault(relativeDirPath, "(根目录)"));
    }
}
