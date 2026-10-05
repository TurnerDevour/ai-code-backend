package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.*;
import com.example.aicodebackend.ai.tools.BaseTool;
import com.example.aicodebackend.ai.tools.ToolManager;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 * <p>
 * 处理器只负责识别消息类型、按名称取到工具实例并统一包装流式格式，
 * 具体展示成什么样由各个工具自己决定（见 {@link BaseTool}）。
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    /**
     * 工具输出的统一包装模板，保证工具消息与 AI 文本之间始终有清晰的分隔
     */
    private static final String TOOL_OUTPUT_TEMPLATE = "\n\n%s\n\n";

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     *
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux, ChatHistoryService chatHistoryService, long appId, User loginUser) {
        // 收集数据用于生成后端记忆格式
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 用于跟踪已经见过的工具ID，判断是否是第一次调用
        Set<String> seenToolIds = new HashSet<>();
        return originFlux
                .map(chunk -> {
                    // 解析每个 JSON 消息块
                    return handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds);
                })
                .filter(StrUtil::isNotEmpty) // 过滤空字串
                .doOnComplete(() -> {
                    // 流式响应完成后，添加 AI 消息到对话历史
                    String aiResponse = chatHistoryStringBuilder.toString();
                    try {
                        chatHistoryService.addChatMessage(appId, loginUser.getId(), aiResponse, ChatMessageTypeEnum.AI);
                    } catch (Exception e) {
                        // 此时 SSE 响应已进入完成阶段，异常逃逸会让整个请求以 500 收尾，
                        // 且响应已按 text/event-stream 提交，Spring 无法再回写业务错误。因此这里只记录日志。
                        log.error("保存 AI 回复到对话历史失败，appId: {}", appId, e);
                    }
                    // 异步构建 Vue 项目
                    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                    try {
                        vueProjectBuilder.buildProjectAsync(projectPath);
                    } catch (Exception e) {
                        log.error("触发 Vue 项目构建失败，appId: {}", appId, e);
                    }
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    try {
                        chatHistoryService.addChatMessage(appId, loginUser.getId(), errorMessage, ChatMessageTypeEnum.AI);
                    } catch (Exception e) {
                        log.error("保存 AI 失败消息到对话历史失败，appId: {}", appId, e);
                    }
                });
    }

    /**
     * 解析并收集 TokenStream 数据
     */
    private String handleJsonMessageChunk(String chunk, StringBuilder chatHistoryStringBuilder, Set<String> seenToolIds) {
        // 解析 JSON
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum == null) {
            return "";
        }
        return switch (typeEnum) {
            case AI_RESPONSE -> handleAiResponseMessage(chunk, chatHistoryStringBuilder);
            case TOOL_REQUEST -> handleToolRequestMessage(chunk, seenToolIds);
            case TOOL_EXECUTED -> handleToolExecutedMessage(chunk, chatHistoryStringBuilder);
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                yield "";
            }
        };
    }

    /**
     * 处理 AI 响应消息：文本增量直接透传给前端并累积到对话历史
     */
    private String handleAiResponseMessage(String chunk, StringBuilder chatHistoryStringBuilder) {
        AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
        String data = StrUtil.nullToEmpty(aiMessage.getData());
        chatHistoryStringBuilder.append(data);
        return data;
    }

    /**
     * 处理工具请求消息：取到对应的工具实例，由工具自己生成展示内容。
     * <p>
     * 同一个工具 ID 只在第一次请求时展示，避免流式过程中重复推送。
     */
    private String handleToolRequestMessage(String chunk, Set<String> seenToolIds) {
        ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
        String toolId = toolRequestMessage.getId();
        if (toolId != null && !seenToolIds.add(toolId)) {
            return "";
        }
        BaseTool tool = findTool(toolRequestMessage.getName());
        return tool == null ? "" : wrapToolOutput(tool.generateToolRequestResponse());
    }

    /**
     * 处理工具执行结果消息：取到对应的工具实例，由工具自己把入参格式化为展示内容。
     * <p>
     * 推送给前端和累积到 chatHistoryStringBuilder（用于落库）的都是完整内容，
     * 保证用户「查看对话」时能看到完整历史；压缩只发生在加载进对话记忆时。
     *
     * @param chunk                    工具执行结果的 JSON 消息
     * @param chatHistoryStringBuilder 累积用于持久化的对话内容
     *
     * @return 需要推送给前端的内容，无需推送时返回空字符串
     */
    private String handleToolExecutedMessage(String chunk, StringBuilder chatHistoryStringBuilder) {
        ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
        BaseTool tool = findTool(toolExecutedMessage.getName());
        if (tool == null) {
            return "";
        }
        JSONObject arguments = parseArguments(toolExecutedMessage);
        if (arguments == null) {
            return "";
        }
        String output = wrapToolOutput(tool.generateToolExecutedResult(arguments));
        if (StrUtil.isEmpty(output)) {
            return "";
        }
        chatHistoryStringBuilder.append(output);
        return output;
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
     * 解析工具执行参数
     *
     * @param toolExecutedMessage 工具执行结果消息
     *
     * @return 参数对象，参数缺失或非法时返回 null
     */
    private JSONObject parseArguments(ToolExecutedMessage toolExecutedMessage) {
        String arguments = toolExecutedMessage.getArguments();
        if (StrUtil.isBlank(arguments)) {
            log.warn("工具执行结果缺少参数，已跳过展示，工具: {}", toolExecutedMessage.getName());
            return null;
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            log.warn("工具参数不是合法 JSON，已跳过展示，工具: {}，参数: {}", toolExecutedMessage.getName(), arguments);
            return null;
        }
    }

    /**
     * 统一包装工具输出，保证工具消息与 AI 文本之间始终有清晰的分隔
     *
     * @param content 工具生成的展示内容
     *
     * @return 包装后的内容，内容为空时返回空字符串
     */
    private String wrapToolOutput(String content) {
        return StrUtil.isBlank(content) ? "" : String.format(TOOL_OUTPUT_TEMPLATE, content.strip());
    }
}
