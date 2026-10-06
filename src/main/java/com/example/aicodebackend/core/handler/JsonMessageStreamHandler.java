package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.*;
import com.example.aicodebackend.ai.tools.BaseTool;
import com.example.aicodebackend.ai.tools.ToolManager;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.constant.ChatHistoryConstant;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 生成收尾处理器：负责把"生成过程"与"客户端连接"解耦（客户端断开后仍等生成跑完再落库）
     * <p>
     * 在容器里由 {@link #initCompletionHandler()} 装配；在单测中直接 new 出该组件时，
     * 用同类内部新建的默认实例兜底（VueProjectBuilder 无状态，见其类注释）。
     */
    private GenerationCompletionHandler completionHandler;

    @jakarta.annotation.PostConstruct
    public void initCompletionHandler() {
        this.completionHandler = new GenerationCompletionHandler(vueProjectBuilder);
    }

    /**
     * 获取收尾处理器（容器外的单测场景下懒加载一个默认实现）
     */
    private GenerationCompletionHandler completionHandler() {
        if (completionHandler == null) {
            completionHandler = new GenerationCompletionHandler(new VueProjectBuilder());
        }
        return completionHandler;
    }

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
        // 同一个工具 ID 只展示一次：累积与下发共用同一份去重集合，
        // 保证"下发内容"与"落库内容"完全一致，也不会重复推送
        Set<String> seenToolIds = new HashSet<>();
        return completionHandler().handle(
                originFlux,
                // 下发给客户端：ai_response 的文本增量、工具请求/结果的展示内容
                chunk -> displayChunk(chunk, seenToolIds),
                // 内部累积（用于落库）：ai_response 文本 + 工具执行结果的完整内容
                (chunk, accumulated) -> accumulateChunk(chunk, seenToolIds),
                chatHistoryService,
                appId,
                loginUser,
                true
        );
    }

    /**
     * 把上游 JSON 消息块转换成"下发给客户端"的内容
     * <p>
     * 生成被中断后已经没有客户端在接收，但这里仍按同样格式产出，
     * 让"客户端展示"与"历史落库"两条路径共用同一套解析逻辑。
     *
     * @param chunk       上游消息块
     * @param seenToolIds 已展示过的工具 ID（避免流式过程中重复推送）
     *
     * @return 需要下发给客户端的内容，无需下发时返回空字符串
     */
    private String displayChunk(String chunk, Set<String> seenToolIds) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum == null) {
            return "";
        }
        return switch (typeEnum) {
            case AI_RESPONSE -> StrUtil.nullToEmpty(JSONUtil.toBean(chunk, AiResponseMessage.class).getData());
            case TOOL_REQUEST -> handleToolRequestMessage(chunk, seenToolIds);
            case TOOL_EXECUTED -> handleToolExecutedMessage(chunk);
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                yield "";
            }
        };
    }

    /**
     * 把上游 JSON 消息块累积成"要落库的历史内容"
     * <p>
     * 与下发给客户端的内容保持一致的语义：AI 文本 + 工具执行结果的完整内容（含文件路径与代码），
     * 保证用户「查看对话」时能看到完整历史。
     *
     * @param chunk       上游消息块
     * @param seenToolIds 已处理过的工具 ID（工具请求只记一次）
     *
     * @return 需要累积的内容
     */
    private String accumulateChunk(String chunk, Set<String> seenToolIds) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        if (typeEnum == null) {
            return "";
        }
        return switch (typeEnum) {
            case AI_RESPONSE -> StrUtil.nullToEmpty(JSONUtil.toBean(chunk, AiResponseMessage.class).getData());
            case TOOL_REQUEST -> handleToolRequestMessage(chunk, seenToolIds);
            case TOOL_EXECUTED -> handleToolExecutedMessage(chunk);
            default -> "";
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
     * 推送给前端与落库的都是完整内容，保证用户「查看对话」时能看到完整历史；
     * 压缩只发生在加载进对话记忆时。
     *
     * @param chunk 工具执行结果的 JSON 消息
     *
     * @return 展示内容（同时用于下发与落库），无需展示时返回空字符串
     */
    private String handleToolExecutedMessage(String chunk) {
        ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
        BaseTool tool = findTool(toolExecutedMessage.getName());
        if (tool == null) {
            return "";
        }
        JSONObject arguments = parseArguments(toolExecutedMessage);
        if (arguments == null) {
            return "";
        }
        return wrapToolOutput(tool.generateToolExecutedResult(arguments));
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
