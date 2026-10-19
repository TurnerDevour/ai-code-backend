package com.example.aicodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.common.PageRequest;
import com.example.aicodebackend.constant.ChatHistoryConstant;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.example.aicodebackend.mapper.ChatHistoryMapper;
import com.example.aicodebackend.model.dto.chathistory.ChatHistoryQueryRequest;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.example.aicodebackend.service.ChatHistoryService;
import com.example.aicodebackend.utils.TextUtils;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    /** 添加对话消息（用户消息、AI 消息、错误消息都通过该方法持久化） */
    @Override
    public Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType) {
        return addChatMessage(appId, userId, message, messageType, null);
    }

    /** 添加对话消息，并关联父消息（用于上下文关联） */
    @Override
    public Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType, Long parentId) {
        return saveChatMessage(appId, userId, message, messageType, parentId, null);
    }

    /**
     * 添加一条 AI 消息，同时记录模型的思考过程
     * 思考过程单独一列落库（{@code chat_history.thinking}），不进 {@code message}：
     * 前端在对话页顶部单独展示它，模型上下文也只读 {@code message}（见 {@link #loadRecentChatHistory}）……
     */
    @Override
    public Long addAiChatMessage(Long appId, Long userId, String message, String thinking) {
        return saveChatMessage(appId, userId, message, ChatMessageTypeEnum.AI, null,
                TextUtils.truncate(thinking, ChatHistoryConstant.MESSAGE_MAX_LENGTH));
    }

    /** 落库对话消息的公共实现 */
    private Long saveChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType,
                                 Long parentId, String thinking) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不合法");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(messageType == null, ErrorCode.PARAMS_ERROR, "消息类型不能为空");

        //     这里只做安全上限兜底，防止异常巨大的模型输出撑爆 message 字段导致插入失败
        String messageToSave = TextUtils.truncate(message, ChatHistoryConstant.MESSAGE_MAX_LENGTH);
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessage(messageToSave);
        chatHistory.setThinking(thinking);
        chatHistory.setMessageType(messageType.getValue());
        chatHistory.setParentId(parentId);

        boolean result = this.save(chatHistory);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "保存对话历史失败");
        return chatHistory.getId();
    }

    /**
     * 删除某个应用的所有对话历史（删除应用时关联删除）
     * 语义是"删完为止"而不是"至少删掉一行"：应用可能还没有任何对话（刚创建就删、或只用过部署），
     * 此时一行都删不到，但这显然是成功。压测时实测过把它当成失败的后果——……
     */
    @Override
    public boolean deleteByAppId(Long appId) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");

        QueryWrapper queryWrapper = QueryWrapper.create().eq("app_id", appId);
        this.remove(queryWrapper);
        return true;
    }

    /** 统计某个应用已有的 AI 消息条数 */
    @Override
    public int countAiMessages(Long appId) {
        if (appId == null || appId <= 0) {
            return 0;
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("app_id", appId)
                .eq("message_type", ChatMessageTypeEnum.AI.getValue());
        return Math.toIntExact(this.count(queryWrapper));
    }

    /** 获取查询条件包装器 */
    @Override
    public QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {

        if (chatHistoryQueryRequest == null) {
            return QueryWrapper.create();
        }

        Long id = chatHistoryQueryRequest.getId();
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        String messageType = chatHistoryQueryRequest.getMessageType();
        String message = chatHistoryQueryRequest.getMessage();
        Long parentId = chatHistoryQueryRequest.getParentId();
        String sortField = chatHistoryQueryRequest.getSortField();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("app_id", appId)
                .eq("user_id", userId)
                .eq("message_type", messageType)
                .eq("parent_id", parentId)
                .like("message", message);

        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, chatHistoryQueryRequest.isAscending());
        } else {
            queryWrapper.orderBy(PageRequest.DEFAULT_SORT_FIELD, false);
        }
        return queryWrapper;
    }

    /** 游标分页查询某个应用的对话历史（每次加载最新的 N 条，支持向前加载更多历史记录） */
    @Override
    public Page<ChatHistory> listAppChatHistoryByPage(Long appId, long pageSize, LocalDateTime lastCreateTime, ChatHistoryQueryRequest chatHistoryQueryRequest) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "每次加载的条数不合法");

        ChatHistoryQueryRequest queryRequest = chatHistoryQueryRequest == null ? new ChatHistoryQueryRequest() : chatHistoryQueryRequest;
        queryRequest.setAppId(appId);
        queryRequest.setLastCreateTime(lastCreateTime);
        queryRequest.setSortField(PageRequest.DEFAULT_SORT_FIELD);
        queryRequest.setSortOrder(PageRequest.DESCEND);
        QueryWrapper queryWrapper = getQueryWrapper(queryRequest);

        if (lastCreateTime != null) {
            queryWrapper.lt(PageRequest.DEFAULT_SORT_FIELD, lastCreateTime);
        }

        return this.page(Page.of(1, pageSize), queryWrapper);
    }

    /**
     * 读取某个应用最近的对话历史（升序，旧的在前）
     * 只做"取数 + 排序"：消息类型过滤、AI 消息的代码块压缩、工具调用文本改写这些
     * 模型上下文相关的加工全部在 ai 层（{@code AiCodeGeneratorServiceFactory#loadHistoryIntoMemory}）……
     */
    @Override
    public List<ChatHistory> loadRecentChatHistory(Long appId, int maxCount) {

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(maxCount <= 0, ErrorCode.PARAMS_ERROR, "加载的历史消息条数不合法");
        try {

            QueryWrapper queryWrapper = QueryWrapper.create()
                    .eq("app_id", appId)
                    .orderBy(PageRequest.DEFAULT_SORT_FIELD, false)
                    .limit(1, maxCount);
            List<ChatHistory> chatHistoryList = this.list(queryWrapper);
            if (CollUtil.isEmpty(chatHistoryList)) {
                log.info("该应用暂无对话历史，appId: {}", appId);
                return List.of();
            }
            return chatHistoryList.reversed();
        } catch (Exception e) {
            // 历史读失败不应该让整个生成请求失败
            log.error("查询对话历史失败，appId: {}", appId, e);
            return List.of();
        }
    }
}
