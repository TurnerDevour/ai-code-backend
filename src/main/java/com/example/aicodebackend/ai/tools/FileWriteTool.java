package com.example.aicodebackend.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class FileWriteTool extends AbstractFileTool {

    /** 连续写入相同内容的容忍次数，超过即跳过 */
    private static final int MAX_IDENTICAL_WRITES = 2;

    /** appId -> 上一次写入指纹与连续重复次数 */
    private static final Map<Long, RepeatState> LAST_WRITES = new ConcurrentHashMap<>();

    private record RepeatState(String fingerprint, int repeats) {
    }

    @Tool("写入文件到指定路径")
    public String writeToFile(@P("文件的相对路径") String relativeFilePath, @P("文件内容") String content,
                              @ToolMemoryId Long appId) {
        if (appId != null) {
            int repeats = nextRepeatCount(appId, relativeFilePath, content);
            if (repeats > MAX_IDENTICAL_WRITES) {
                log.warn("检测到重复写入相同内容（连续第 {} 次），已跳过：appId={}, 文件={}",
                        repeats, appId, relativeFilePath);
                return String.format("已跳过写入：%s 里已经就是这份内容（这是连续第 %d 次一模一样的写入）。"
                                + "请不要重复写同一个文件：如果还有别的文件没写，请继续写其它文件；"
                                + "如果所有文件都写完了，请直接输出生成完毕提示。",
                        relativeFilePath, repeats);
            }
        }
        try {
            Path path = resolveProjectPath(relativeFilePath, appId);
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            Files.write(path, StrUtil.nullToEmpty(content).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("文件写入成功，文件路径：{}", path.toAbsolutePath());
            return "文件写入成功，文件路径：" + relativeFilePath;
        } catch (PathOutsideProjectException e) {
            String rejected = String.format("拒绝写入项目目录之外的路径：%s（项目目录：%s）",
                    relativeFilePath, e.projectRoot());
            log.warn("{}, appId={}", rejected, appId);
            return rejected;
        } catch (Exception e) {
            String errorMessage = String.format("写入文件失败，文件路径：%s，错误类型：%s，错误信息：%s",
                    relativeFilePath, e.getClass().getSimpleName(), e.getMessage());
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    /** 记录并返回连续重复次数 */
    private int nextRepeatCount(Long appId, String relativeFilePath, String content) {
        String fingerprint = relativeFilePath + "\u0000" + content;
        RepeatState state = LAST_WRITES.compute(appId, (key, previous) ->
                previous != null && previous.fingerprint().equals(fingerprint)
                        ? new RepeatState(fingerprint, previous.repeats() + 1)
                        : new RepeatState(fingerprint, 1));
        return state.repeats();
    }

    @Override
    public String getToolName() {
        return "writeToFile";
    }

    @Override
    public String getDisplayName() {
        return "写入文件内容";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath", arguments.getStr("relativePath", ""));
        String content = StrUtil.nullToEmpty(arguments.getStr("content"));
        if (StrUtil.isBlank(relativeFilePath)) {
            log.warn("工具执行结果缺少文件路径，已跳过展示");
            return "";
        }
        String suffix = StrUtil.blankToDefault(FileUtil.getSuffix(relativeFilePath), "text");
        return String.format("""
                %s
                ```%s
                %s
                ```""", toolCallHeader(relativeFilePath), suffix, content);
    }
}
