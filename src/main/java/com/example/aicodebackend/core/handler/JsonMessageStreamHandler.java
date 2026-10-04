package com.example.aicodebackend.core.handler;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.*;
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
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private VueProjectBuilder vueProjectBuilder;

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
        if (typeEnum != null) {
            switch (typeEnum) {
                case AI_RESPONSE -> {
                    AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                    String data = aiMessage.getData();
                    // 直接拼接响应
                    chatHistoryStringBuilder.append(data);
                    return data;
                }
                case TOOL_REQUEST -> {
                    ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                    String toolId = toolRequestMessage.getId();
                    // 检查是否是第一次看到这个工具 ID
                    if (toolId != null && !seenToolIds.contains(toolId)) {
                        // 第一次调用这个工具，记录 ID 并完整返回工具信息
                        seenToolIds.add(toolId);
                        return "\n\n[🔧 选择工具] 写入文件\n\n";
                    } else {
                        // 不是第一次调用这个工具，直接返回空
                        return "";
                    }
                }
                case TOOL_EXECUTED -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                    return handleToolExecutedMessage(toolExecutedMessage, chatHistoryStringBuilder);
                }
                default -> {
                    log.error("不支持的消息类型: {}", typeEnum);
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * 处理工具执行结果消息：把写入的文件路径与内容格式化为前端可直接渲染的 Markdown 代码块。
     * <p>
     * 注意：FileWriteTool#writeToFile 的形参名是 relativePath，模型返回的 arguments 也使用该键名，
     * 因此这里以 relativePath 为准，同时兼容 relativeFilePath 以避免历史数据解析失败。
     * <p>
     * 推送给前端和累积到 chatHistoryStringBuilder（用于落库）的都是完整文件内容，
     * 保证用户「查看对话」时能看到完整历史；压缩只发生在加载进对话记忆时。
     *
     * @param toolExecutedMessage    工具执行结果消息
     * @param chatHistoryStringBuilder 累积用于持久化的对话内容
     *
     * @return 需要推送给前端的内容，无需推送时返回空字符串
     */
    private String handleToolExecutedMessage(ToolExecutedMessage toolExecutedMessage, StringBuilder chatHistoryStringBuilder) {
        String arguments = toolExecutedMessage.getArguments();
        if (StrUtil.isBlank(arguments)) {
            log.warn("工具执行结果缺少参数，已跳过展示，工具: {}", toolExecutedMessage.getName());
            return "";
        }
        JSONObject jsonObject;
        try {
            jsonObject = JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            log.warn("工具参数不是合法 JSON，已跳过展示，工具: {}，参数: {}", toolExecutedMessage.getName(), arguments);
            return "";
        }
        String relativeFilePath = jsonObject.getStr("relativePath", jsonObject.getStr("relativeFilePath", ""));
        String content = jsonObject.getStr("content", "");
        if (StrUtil.isBlank(relativeFilePath)) {
            log.warn("工具执行结果缺少文件路径，已跳过展示，工具: {}", toolExecutedMessage.getName());
            return "";
        }
        String suffix = StrUtil.blankToDefault(FileUtil.getSuffix(relativeFilePath), "text");
        // 推送给前端和落库的内容都保持完整，保证用户「查看对话」时能看到完整历史；
        // 进入模型上下文的压缩统一在加载对话记忆时处理（见 ChatHistoryServiceImpl#loadChatHistoryToMemory）
        String output = String.format("\n\n%s\n\n", formatFileBlock(relativeFilePath, suffix, content));
        chatHistoryStringBuilder.append(output);
        return output;
    }

    /**
     * 把文件写入结果格式化为 Markdown 代码块
     *
     * @param relativeFilePath 文件相对路径
     * @param suffix           代码块语言标识
     * @param content          代码块内容
     *
     * @return 格式化后的 Markdown 文本
     */
    private String formatFileBlock(String relativeFilePath, String suffix, String content) {
        return String.format("""
                [🔧 工具调用] 写入文件 %s
                ```%s
                %s
                ```
                """, relativeFilePath, suffix, content);
    }
}
