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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Component
public class FileWriteTool extends BaseTool {

    /**
     * 同一个应用连续写入"完全相同内容"的容忍次数：第 3 次开始跳过写入并提示模型
     * <p>
     * 为什么需要它：模型一旦因为某种原因（记忆里看不到工具结果、上下文被挤掉等）以为文件没写成功，
     * 就会无限重复写入同一批文件。而"重复写入"这件事本身有确定答案——文件里已经是这份内容了，
     * 再写一次没有任何效果。与其等框架在工具轮次上限处兜底失败，不如在这里直接把循环打破：
     * 跳过无效写入，并明确告诉模型"这个文件已经是这个内容了，请继续别的文件或者收尾"。
     */
    private static final int MAX_IDENTICAL_WRITES = 2;

    /**
     * appId -> 上一次写入的指纹与连续重复次数
     * <p>
     * 每个应用只保留最近一次写入，用于识别"连续重复"；内容一变就重新计数，
     * 因此跨轮次的正常重写不会被误判（即使误判，代价也只是跳过一次内容完全相同的写入）。
     */
    private static final Map<Long, RepeatState> LAST_WRITES = new ConcurrentHashMap<>();

    /**
     * 上一次写入的指纹与连续重复次数
     *
     * @param fingerprint 文件路径 + 内容的指纹
     * @param repeats     连续写入相同指纹的次数
     */
    private record RepeatState(String fingerprint, int repeats) {
    }

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
        // 1. 重复写入断路器：先判断本次是不是"和上一次完全一样"
        if (appId != null) {
            String fingerprint = relativeFilePath + "\u0000" + content;
            RepeatState state = LAST_WRITES.compute(appId, (key, previous) ->
                    previous != null && previous.fingerprint().equals(fingerprint)
                            ? new RepeatState(fingerprint, previous.repeats() + 1)
                            : new RepeatState(fingerprint, 1));
            if (state.repeats() > MAX_IDENTICAL_WRITES) {
                log.warn("检测到重复写入相同内容（连续第 {} 次），已跳过：appId={}, 文件={}",
                        state.repeats(), appId, relativeFilePath);
                return String.format(
                        "已跳过写入：%s 里已经就是这份内容（这是连续第 %d 次一模一样的写入）。"
                                + "请不要重复写同一个文件：如果还有别的文件没写，请继续写其它文件；"
                                + "如果所有文件都写完了，请直接输出生成完毕提示。",
                        relativeFilePath, state.repeats());
            }
        }
        try {
            Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId).normalize();
            Path path = Paths.get(relativeFilePath);
            if (path.isAbsolute()) {
                // 模型偶尔给出绝对路径：允许写，但必须在项目目录之内（见下面的越界校验）
                path = path.normalize();
            } else {
                path = projectRoot.resolve(relativeFilePath).normalize();
            }
            // 越界保护：任何情况下都不能写到项目目录之外
            if (!path.startsWith(projectRoot)) {
                String rejected = String.format("拒绝写入项目目录之外的路径：%s（项目目录：%s）",
                        relativeFilePath, projectRoot);
                log.warn("{}, appId={}", rejected, appId);
                return rejected;
            }

            // 创建父目录(如果不存在)
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            // 写入文件内容
            Files.write(path, StrUtil.nullToEmpty(content).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("文件写入成功，文件路径：{}", path.toAbsolutePath());
            return "文件写入成功，文件路径：" + relativeFilePath;
        } catch (Exception e) {
            // 必须捕获 Exception 而不只是 IOException：路径非法（InvalidPathException）这类
            // RuntimeException 以前会直接逃出工具，变成一条"看不出失败"的工具结果——
            // 模型以为写成功、用户看到 AI 说改好了但文件根本没写（实测问题）。
            String errorMessage = String.format("写入文件失败，文件路径：%s，错误类型：%s，错误信息：%s",
                    relativeFilePath, e.getClass().getSimpleName(), e.getMessage());
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
