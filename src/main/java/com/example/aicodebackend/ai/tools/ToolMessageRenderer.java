package com.example.aicodebackend.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具调用消息的展示文本生成
 * <p>
 * 为什么要有这个类（实测问题）：实时 SSE 只下发 {@code name / arguments / result} 三个原始字段，
 * 前端拿到后只能自己"猜"每个工具的参数长什么样。结果前端把 {@code modifyFile} 也当成
 * {@code writeToFile} 渲染——显示「写入文件 src/pages/HomePage.vue」配一个<b>空代码块</b>
 * （因为 modifyFile 的参数是 oldContent / newContent，根本没有 content 字段），
 * 用户看到的就是"AI 调用工具写文件输出空白"，而文件其实已经被正确修改（预览正常）。
 * 更早的 {@code [🔧 选择工具] 写入文件} 也是同一种硬编码：不管调哪个工具都写"写入文件"。
 * <p>
 * 工具自己最清楚自己的参数结构（{@link BaseTool#generateToolExecutedResult}），
 * 所以展示文本统一在后端生成、随消息一起下发，前端只负责渲染文本。
 * 新增工具时只需要实现 BaseTool 的展示方法，前后端都不用再改。
 */
@Slf4j
@Component
public class ToolMessageRenderer {

    /**
     * 工具执行失败的展示文案里保留的最大字符数（失败原因可能是很长的异常堆栈信息）
     */
    private static final int FAILURE_REASON_MAX_LENGTH = 500;

    private final ToolManager toolManager;

    public ToolMessageRenderer(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /**
     * 生成"选择工具"阶段的展示文本（形如 {@code [🔧 选择工具] 修改文件内容}）
     *
     * @param toolName 工具英文名称
     *
     * @return 展示文本，工具未注册时返回空字符串
     */
    public String renderRequest(String toolName) {
        BaseTool tool = findTool(toolName);
        return tool == null ? "" : StrUtil.nullToEmpty(tool.generateToolRequestResponse());
    }

    /**
     * 生成"工具执行完成"阶段的展示文本（写入的文件内容 / 修改前后对比 / 失败原因）
     *
     * @param toolName  工具英文名称
     * @param arguments 工具入参 JSON
     * @param failed    是否执行失败（参数不合法 / 工具名不存在 / 工具内部异常）
     * @param result    工具返回值（失败时即失败原因）
     *
     * @return 展示文本，无需展示时返回空字符串
     */
    public String renderExecuted(String toolName, String arguments, boolean failed, String result) {
        // 失败的工具调用不能渲染成"写入了文件"：LangChain4j 对参数不合法 / 工具名不存在 / 工具异常
        // 同样会回调 onToolExecuted，此时文件并没有写入（实测问题：AI 说改了、页面没变）。
        if (failed) {
            return String.format("⚠️ [工具调用失败] %s：%s", StrUtil.nullToEmpty(toolName),
                    StrUtil.maxLength(StrUtil.nullToEmpty(result), FAILURE_REASON_MAX_LENGTH));
        }
        BaseTool tool = findTool(toolName);
        if (tool == null) {
            return "";
        }
        JSONObject parsed = parseArguments(toolName, arguments);
        if (parsed == null) {
            return "";
        }
        try {
            return StrUtil.nullToEmpty(tool.generateToolExecutedResult(parsed));
        } catch (Exception e) {
            // 工具自己的展示逻辑出错不能影响生成：退回"只有工具名"的纯文本，至少不让用户看到空白
            log.warn("生成工具展示文本失败，工具: {}，错误: {}", toolName, e.getMessage());
            return "[🔧 工具调用] " + tool.getDisplayName();
        }
    }

    /**
     * 按工具名称取出工具实例
     *
     * @param toolName 工具英文名称
     *
     * @return 工具实例，未注册时返回 null
     */
    private BaseTool findTool(String toolName) {
        BaseTool tool = StrUtil.isBlank(toolName) ? null : toolManager.getTool(toolName);
        if (tool == null) {
            log.warn("未注册的工具，已跳过展示: {}", toolName);
        }
        return tool;
    }

    /**
     * 解析工具入参
     *
     * @param toolName  工具英文名称（仅用于日志）
     * @param arguments 工具入参 JSON
     *
     * @return 入参对象，缺失或非法时返回 null
     */
    private JSONObject parseArguments(String toolName, String arguments) {
        if (StrUtil.isBlank(arguments)) {
            log.warn("工具执行结果缺少参数，已跳过展示，工具: {}", toolName);
            return null;
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            log.warn("工具参数不是合法 JSON，已跳过展示，工具: {}，参数: {}", toolName, StrUtil.maxLength(arguments, 200));
            return null;
        }
    }
}
