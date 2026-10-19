package com.example.aicodebackend.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.*;
import com.example.aicodebackend.ai.tools.ToolMessageRenderer;
import com.example.aicodebackend.core.builder.VueProjectBuilder;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理流式响应里的结构化消息：正文增量、思考过程、工具请求与工具执行结果
 * 处理器只负责识别消息类型、按名称取到工具实例并统一包装流式格式……
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    /** 工具输出的统一包装模板，保证工具消息与 AI 文本之间始终有清晰的分隔 */
    private static final String TOOL_OUTPUT_TEMPLATE = "\n\n%s\n\n";

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    /** 工具展示文本生成器：工具请求/执行结果的展示文案统一由它生成（工具自己声明） */
    @Resource
    private ToolMessageRenderer toolMessageRenderer;

    /**
     * 生成收尾处理器：负责把"生成过程"与"客户端连接"解耦（客户端断开后仍等生成跑完再落库）
     * 在容器里由 {@link #initCompletionHandler()} 装配；在单测中直接 new 出该组件时，
     * 用同类内部新建的默认实例兜底（VueProjectBuilder 无状态，见其类注释）。
     */
    private GenerationCompletionHandler completionHandler;

    @jakarta.annotation.PostConstruct
    public void initCompletionHandler() {
        this.completionHandler = new GenerationCompletionHandler(vueProjectBuilder);
    }

    /** 获取收尾处理器（容器外的单测场景下懒加载一个默认实现） */
    private GenerationCompletionHandler completionHandler() {
        if (completionHandler == null) {
            completionHandler = new GenerationCompletionHandler(new VueProjectBuilder());
        }
        return completionHandler;
    }

    /**
     * 处理 TokenStream（VUE_PROJECT）
     * 解析 JSON 消息并重组为完整的响应格式
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
     * 处理 HTML / 多文件模式的消息流（无工具，只有正文与思考两条增量）
     * 与 Vue 工程模式的两点差别：
     * 没有别的地方能把思考内容存下来（前端因此看不到「AI 思考过程」面板）。</li>
     */
    public Flux<String> handleGeneratedFiles(Flux<String> originFlux, ChatHistoryService chatHistoryService,
                                            long appId, User loginUser) {
        // 这两个模式没有工具，去重集合只是为了让两个通道共用同一套解析逻辑
        Set<String> seenToolIds = new HashSet<>();
        return completionHandler().handle(
                originFlux,
                // 下发给客户端：正文增量 + 思考过程（原样透传 ai_thinking 消息）
                chunk -> displayChunk(chunk, seenToolIds),
                // 内部累积（用于落库）：只收正文，思考过程不属于正文
                (chunk, accumulated) -> accumulateChunk(chunk, seenToolIds),
                // 思考过程单独累积，收尾时写进 chat_history.thinking
                JsonMessageStreamHandler::thinkingText,
                chatHistoryService,
                appId,
                loginUser,
                false
        );
    }

    /** 取出思考消息里的文本（非思考消息返回空串），供落库时单独累积 */
    private static String thinkingText(String chunk) {
        StreamMessageTypeEnum typeEnum = messageType(chunk);
        if (typeEnum != StreamMessageTypeEnum.AI_THINKING) {
            return "";
        }
        return StrUtil.nullToEmpty(JSONUtil.toBean(chunk, AiThinkingMessage.class).getData());
    }

    /**
     * 把上游 JSON 消息块转换成"下发给客户端"的内容
     * 生成被中断后已经没有客户端在接收，但这里仍按同样格式产出，
     * 让"客户端展示"与"历史落库"两条路径共用同一套解析逻辑。
     */
    private String displayChunk(String chunk, Set<String> seenToolIds) {
        StreamMessageTypeEnum typeEnum = messageType(chunk);
        if (typeEnum == null) {
            return "";
        }
        if (typeEnum == StreamMessageTypeEnum.AI_THINKING) {
            return chunk;
        }
        if (typeEnum == StreamMessageTypeEnum.ERROR) {
            log.error("不支持的消息类型: {}", typeEnum);
            return "";
        }
        return extractContent(chunk, typeEnum, seenToolIds);
    }

    /** 把上游 JSON 消息块累积成"要落库的历史内容" */
    private String accumulateChunk(String chunk, Set<String> seenToolIds) {
        StreamMessageTypeEnum typeEnum = messageType(chunk);
        if (typeEnum == null || typeEnum == StreamMessageTypeEnum.AI_THINKING) {
            return "";
        }
        return extractContent(chunk, typeEnum, seenToolIds);
    }

    /** 解析消息类型，未知类型返回 null */
    private static StreamMessageTypeEnum messageType(String chunk) {
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        return StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
    }

    /** 正文与工具消息的公共提取逻辑（下发与落库共用） */
    private String extractContent(String chunk, StreamMessageTypeEnum typeEnum, Set<String> seenToolIds) {
        return switch (typeEnum) {
            case AI_RESPONSE -> StrUtil.nullToEmpty(JSONUtil.toBean(chunk, AiResponseMessage.class).getData());
            case TOOL_REQUEST -> handleToolRequestMessage(chunk, seenToolIds);
            case TOOL_EXECUTED -> handleToolExecutedMessage(chunk);
            default -> "";
        };
    }

    /**
     * 处理工具请求消息：取到对应的工具实例，由工具自己生成展示内容。
     * 同一个工具 ID 只在第一次请求时展示，避免流式过程中重复推送。
     */
    private String handleToolRequestMessage(String chunk, Set<String> seenToolIds) {
        ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
        String toolId = toolRequestMessage.getId();
        if (toolId != null && !seenToolIds.add(toolId)) {
            return "";
        }
        // 展示文案统一由 ToolMessageRenderer 生成（工具自己声明，前端/历史/实时三处一致）
        return wrapToolOutput(toolMessageRenderer.renderRequest(toolRequestMessage.getName()));
    }

    /**
     * 处理工具执行结果消息：取到对应的工具实例，由工具自己把入参格式化为展示内容。
     * 推送给前端与落库的都是完整内容，保证用户「查看对话」时能看到完整历史；
     * 压缩只发生在加载进对话记忆时。
     */
    private String handleToolExecutedMessage(String chunk) {
        ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
        // 失败的工具调用不能渲染成"写入了文件"：LangChain4j 对参数不合法 / 工具名不存在 / 工具异常
        // 同样会回调 onToolExecuted，此时文件并没有写入（实测问题：AI 说改了、页面没变）。
        return wrapToolOutput(toolMessageRenderer.renderExecuted(toolExecutedMessage.getName(),
                toolExecutedMessage.getArguments(), toolExecutedMessage.isFailed(), toolExecutedMessage.getResult()));
    }

    /** 统一包装工具输出，保证工具消息与 AI 文本之间始终有清晰的分隔 */
    private String wrapToolOutput(String content) {
        return StrUtil.isBlank(content) ? "" : String.format(TOOL_OUTPUT_TEMPLATE, content.strip());
    }
}
