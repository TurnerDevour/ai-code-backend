package com.example.aicodebackend.config;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.ai.provider.StreamingChatModelRegistry;
import com.example.aicodebackend.ai.tools.*;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import com.example.aicodebackend.utils.TextUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    /**
     * Vue 工程模式下允许的最大工具轮次（LLM ↔ 工具 往返次数）
     * LangChain4j 的默认值是 100，而一次往返包含一次完整的模型调用（推理模型通常几秒到几十秒），
     * 一旦模型陷入"反复重写同一批文件"，100 轮在用户看来就是"死循环"……
     */
    private static final int VUE_MAX_TOOL_CALL_ROUND_TRIPS = 60;

    /**
     * 按 AI 模型类型取流式模型的注册表，见 {@link StreamingChatModelRegistry}。
     * 这里只依赖注册表、不再逐个注入模型 Bean：容器里可能存在多个 {@link StreamingChatModel}
     * （含 starter 自动装配的那个），逐个 @Qualifier 注入既容易歧义，也让"新增一个模型"必须改本类构造函数。
     */
    private final StreamingChatModelRegistry streamingChatModelRegistry;

    private final ChatMemoryStore chatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    private final ToolManager toolManager;

    public AiCodeGeneratorServiceFactory(
            StreamingChatModelRegistry streamingChatModelRegistry,
            ToolManager toolManager,
            ChatMemoryStore chatMemoryStore,
            ChatHistoryService chatHistoryService
    ) {
        this.streamingChatModelRegistry = streamingChatModelRegistry;
        this.chatMemoryStore = chatMemoryStore;
        this.chatHistoryService = chatHistoryService;
        this.toolManager = toolManager;
    }

    /**
     * 使用 Caffeine 缓存来存储 AiCodeGeneratorService 实例,以 appId + 代码生成类型 + 模型类型 为 key，
     * 最大缓存数量为 1000，写入后 30 分钟过期，访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                // 当缓存项被移除时，可以在这里进行一些清理操作
                log.info("AI 服务实例已被移除，cacheKey: {}, 原因: {}", key, cause);
            })
            .build();

    /** 根据 appId 获取服务（带缓存）这个方法是为了兼容历史逻辑 */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML, AIModelTypeEnum.DEFAULT_MODEL);
    }

    /** 根据 appId 和代码生成类型获取服务（带缓存） */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        return getAiCodeGeneratorService(appId, codeGenType, AIModelTypeEnum.DEFAULT_MODEL);
    }

    /** 根据 appId、代码生成类型和 AI 模型类型获取服务（带缓存） */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType, AIModelTypeEnum aiModelType) {
        AIModelTypeEnum modelType = aiModelType == null ? AIModelTypeEnum.DEFAULT_MODEL : aiModelType;
        // 新一轮生成开始前的记忆自愈：把"上一轮异常中断留下的不完整工具上下文"清掉。
        // 必须在这里（而不是记忆读取路径）做，原因见 ChatMemorySanitizer#repairStoredMessages 的说明。
        repairStoredMemory(appId);
        String cacheKey = buildCacheKey(appId, codeGenType, modelType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType, modelType));
    }

    /**
     * 修复存储里的不完整工具上下文（新一轮开始时调用一次）
     * 修复失败不影响本轮生成：脏数据最多让这一轮被接口拒绝，用户重试时会再修一次。
     */
    private void repairStoredMemory(long appId) {
        if (appId <= 0) {
            return;
        }
        try {
            int removed = ChatMemorySanitizer.repairStoredMessages(chatMemoryStore, appId);
            if (removed > 0) {
                log.warn("对话记忆自愈：清理了上一轮残留的不完整工具上下文，appId={}, 消息数={}", appId, removed);
            }
        } catch (Exception e) {
            log.warn("对话记忆自愈失败（不影响本轮生成）：appId={}, error={}", appId, e.getMessage());
        }
    }

    /**
     * 失效某个应用缓存的生成服务
     * 使用场景：一次生成因为"assistant 消息带 tool_calls 却没有工具结果"这类记忆损坏而失败时，
     * 已经缓存的 {@link AiCodeGeneratorService} 内部持有损坏的记忆快照，不清掉它……
     */
    public synchronized int invalidateAiCodeGeneratorService(long appId) {
        String prefix = appId + "_";
        List<String> keys = serviceCache.asMap().keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .toList();
        serviceCache.invalidateAll(keys);
        if (!keys.isEmpty()) {
            log.warn("已失效应用的生成服务缓存，appId: {}, 条目数: {}, keys: {}", appId, keys.size(), keys);
        }
        return keys.size();
    }

    /**
     * 把数据库里最近的对话装进记忆（窗口裁剪语义与改造前一致）
     * 为什么要"清空后重建"：数据库是对话历史的唯一事实源，记忆存储（Redis）里可能残留
     * 上一轮异常中断留下的副本（例如带 tool_calls 却没有结果的消息）。每次创建实例都按 DB 重建……
     */
    protected void loadHistoryIntoMemory(long appId, MessageWindowChatMemory chatMemory) {
        List<ChatHistory> histories = chatHistoryService.loadRecentChatHistory(appId,
                ChatHistoryConstant.MEMORY_MAX_MESSAGES);
        if (histories == null || histories.isEmpty()) {
            // 与改造前保持一致：没有历史时不清空记忆（这时 Redis 里的内容就是唯一上下文）
            return;
        }
        try {
            chatMemory.clear();
            int loaded = 0;
            for (ChatHistory history : histories) {
                ChatMessage message = toChatMessage(history);
                if (message == null) {
                    continue;
                }
                chatMemory.add(message);
                loaded++;
            }
            log.info("加载对话历史到记忆成功，appId: {}, 加载条数: {}", appId, loaded);
        } catch (Exception e) {
            // 记忆存储（Redis）不可用时按"没有历史"继续：历史装载失败不应该让整轮生成失败
            // （这条容错是从改造前 ChatHistoryServiceImpl 里原样保留下来的）
            log.error("加载对话历史到记忆失败（按无历史继续），appId: {}", appId, e);
        }
    }

    /**
     * 数据库记录转换成模型上下文消息
     * 用户消息就是真实指令，原样进入上下文；AI 消息先改写工具调用记录、再压缩代码块、最后兜底截断
     * （完整源码只留在数据库里供用户查看历史，不参与多轮上下文，否则会撑爆上下文与费用）……
     */
    protected static ChatMessage toChatMessage(ChatHistory history) {
        String type = history.getMessageType();
        String text = history.getMessage();
        if (ChatMessageTypeEnum.USER.getValue().equals(type)) {
            return StrUtil.isBlank(text) ? null : UserMessage.from(text);
        }
        if (ChatMessageTypeEnum.AI.getValue().equals(type)) {
            String context = TextUtils.truncate(
                    // 先改写工具调用记录（否则模型会照抄这个文本格式"假装"调用工具，实测导致 0 次真实调用），
                    // 再压缩代码块、最后兜底截断：改写放在最前面，能顺手丢掉巨型入参，省下大量 token。
                    TextUtils.compressCodeBlocks(
                            TextUtils.neutralizeToolCallRecords(text),
                            ChatHistoryConstant.MEMORY_CODE_BLOCK_MAX_LENGTH),
                    ChatHistoryConstant.MEMORY_MESSAGE_MAX_LENGTH);
            return StrUtil.isBlank(context) ? null : AiMessage.from(context);
        }
        return null;
    }

    /**
     * 实际创建服务实例（缓存未命中时调用）
     * 单独抽成 protected 方法是为了让单测可以子类化替换成桩服务：验证"缺少 CSS/JS 时的补全"
     * 不应该真的去调用模型。
     */
    protected AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum, AIModelTypeEnum aiModelTypeEnum) {
        log.info("Creating new AiCodeGeneratorService instance for appId: {}, codeGenType: {}, aiModelType: {}",
                appId, codeGenTypeEnum.getValue(), aiModelTypeEnum.getValue());
        // 根据 appId 创建一个新的 MessageWindowChatMemory 实例
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(ChatHistoryConstant.MEMORY_MAX_MESSAGES)
                .build();

        // 对话记忆初始化时，从数据库中加载该应用的历史对话，保证 AI 能记住之前的上下文
        // appId 为 0 表示不关联具体应用的默认实例，无需加载历史
        if (appId > 0) {
            loadHistoryIntoMemory(appId, chatMemory);
        }

        // 按应用所选的 AI 模型类型取流式模型（未配置或初始化失败时抛出带排查指引的业务异常）
        StreamingChatModel selectedStreamingChatModel = streamingChatModelRegistry.get(aiModelTypeEnum);

        // 使用CodeGenTypeEnum来决定生成模式
        return switch (codeGenTypeEnum) {
            case VUE_PROJECT -> {
                BaseTool[] tools = toolManager.getAllTools();
                if (tools == null || tools.length == 0) {
                    // 没有工具 = 模型的每一次工具调用都会走"工具名不存在"分支：文件一个都不会写，
                    // 但对话里照样显示"[工具调用] writeToFile ..."（langchain4j 对失败同样回调），
                    // 表现为"AI 说改了、页面没变"。这里必须大声报出来，便于第一时间定位装配问题。
                    log.error("Vue 工程模式未注册任何文件工具，生成将无法写入文件！请检查 Bean 扫描与 ToolManager 注入");
                } else {
                    log.info("Vue 工程模式已注册 {} 个文件工具：{}", tools.length,
                            java.util.Arrays.stream(tools).map(BaseTool::getToolName).toList());
                }
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .streamingChatModel(selectedStreamingChatModel)
                        .chatMemoryProvider((memoryId) -> chatMemory)
                        .tools(tools == null ? new Object[0] : tools)
                        .hallucinatedToolNameStrategy(
                                toolExecutionRequest -> ToolExecutionResultMessage.from(toolExecutionRequest, "错误：不存在这个工具， " + toolExecutionRequest.name())
                        )
                        .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                        .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                        // 工具轮次上限：LangChain4j 默认 100 轮，对推理模型来说一轮几十秒，
                        // 真出现"模型反复写同一批文件"时 100 轮约等于"永不结束"（用户看到的就是死循环）。
                        // 收敛在 60 轮：正常 Vue 工程（文件数 < 30）远用不到，超限时立刻失败而不是空转。
                        .maxToolCallingRoundTrips(VUE_MAX_TOOL_CALL_ROUND_TRIPS)
                        .build();
            }
            case HTML, MULTI_FILE -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(selectedStreamingChatModel)
                    .chatMemory(chatMemory)
                    .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型: " + codeGenTypeEnum.getValue());
        };
    }

    /**
     * 默认生成服务（兼容历史注入点，当前业务代码走 {@link #getAiCodeGeneratorService}）
     * 必须 {@code @Lazy}：它默认用 deepseek-v4.1-flash，而模型注册表对"配置缺失的模型"是"标记不可用、
     * 选中时才报错"（目的是让某个平台的 Key / 环境变量没配好时应用仍能启动、其它模型仍可用）……
     */
    @Lazy
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }

    /** 构建缓存键 */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType, AIModelTypeEnum aiModelType) {
        return appId + "_" + codeGenType.getValue() + "_" + aiModelType.getValue();
    }
}
