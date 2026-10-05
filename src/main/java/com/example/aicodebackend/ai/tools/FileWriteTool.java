package com.example.aicodebackend.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.example.aicodebackend.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;


@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    /**
     * 写入文件到指定路径
     *
     * @param relativeFilePath 文件的相对路径
     * @param content      文件内容
     *
     * @return 写入结果
     */
    @Tool("写入文件到指定路径")
    public String writeToFile(@P("文件的相对路径") String relativeFilePath, @P("文件内容") String content, @ToolMemoryId Long appId) {
        try {
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                String projectDirname = "vue_project_" + appId;
                Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirname);
                path = projectRoot.resolve(relativeFilePath);
            }

            // 创建父目录(如果不存在)
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("文件写入成功，文件路径：{}", path.toAbsolutePath());
            return "文件写入成功，文件路径：" + relativeFilePath;
        } catch (IOException e) {
            String errorMessage = String.format("写入文件失败，文件路径：%s，错误信息：%s", relativeFilePath, e.getMessage());
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "writeToFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件内容";
    }

    /**
     * 把写入的文件路径与内容格式化为前端可直接渲染的 Markdown 代码块
     * <p>
     * 这里返回的是完整文件内容，推送给前端和落库的内容都保持完整，
     * 保证用户「查看对话」时能看到完整历史；进入模型上下文的压缩统一在加载对话记忆时处理
     * （见 ChatHistoryServiceImpl#loadChatHistoryToMemory）。
     *
     * @param arguments 工具执行参数
     *
     * @return 格式化后的 Markdown 文本，参数缺失时返回空字符串
     */
    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        // 模型返回的参数名与 writeToFile 的形参同名，均为 relativeFilePath；同时兼容历史数据里的 relativePath
        String relativeFilePath = arguments.getStr("relativeFilePath", arguments.getStr("relativePath", ""));
        String content = StrUtil.nullToEmpty(arguments.getStr("content"));
        if (StrUtil.isBlank(relativeFilePath)) {
            log.warn("工具执行结果缺少文件路径，已跳过展示");
            return "";
        }
        String suffix = StrUtil.blankToDefault(FileUtil.getSuffix(relativeFilePath), "text");
        return String.format("""
                [🔧 工具调用] %s %s
                ```%s
                %s
                ```""", getDisplayName(), relativeFilePath, suffix, content);
    }
}
