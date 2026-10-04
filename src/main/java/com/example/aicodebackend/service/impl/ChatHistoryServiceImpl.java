package com.example.aicodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    /**
     * 添加对话消息（用户消息、AI 消息、错误消息都通过该方法持久化）
     *
     * @param appId       应用id
     * @param userId      创建用户id
     * @param message     消息内容
     * @param messageType 消息类型
     *
     * @return 新消息的id
     */
    @Override
    public Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType) {
        return addChatMessage(appId, userId, message, messageType, null);
    }

    /**
     * 添加对话消息，并关联父消息（用于上下文关联）
     *
     * @param appId       应用id
     * @param userId      创建用户id
     * @param message     消息内容
     * @param messageType 消息类型
     * @param parentId    父消息id
     *
     * @return 新消息的id
     */
    @Override
    public Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType, Long parentId) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不合法");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(messageType == null, ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        // 2. 构建对话历史实体
        // 2.1 用户消息原样保存；AI / 错误消息可能包含工具写入的代码内容，统一限长，避免单条消息过大
        String messageToSave = ChatMessageTypeEnum.USER.equals(messageType)
                ? message
                : TextUtils.truncate(message, ChatHistoryConstant.MESSAGE_MAX_LENGTH);
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessage(messageToSave);
        chatHistory.setMessageType(messageType.getValue());
        chatHistory.setParentId(parentId);
        // 3. 保存消息
        boolean result = this.save(chatHistory);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "保存对话历史失败");
        return chatHistory.getId();
    }

    /**
     * 删除某个应用的所有对话历史（删除应用时关联删除）
     *
     * @param appId 应用id
     *
     * @return 是否删除成功
     */
    @Override
    public boolean deleteByAppId(Long appId) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        // 2. 根据应用id删除对话历史（逻辑删除，不会真正删除数据）
        QueryWrapper queryWrapper = QueryWrapper.create().eq("app_id", appId);
        return this.remove(queryWrapper);
    }

    /**
     * 获取查询条件包装器
     *
     * @param chatHistoryQueryRequest 对话历史查询请求对象
     *
     * @return 查询条件包装器
     */
    @Override
    public QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        // 1. 校验参数
        if (chatHistoryQueryRequest == null) {
            return QueryWrapper.create();
        }

        // 2. 创建查询条件包装器
        Long id = chatHistoryQueryRequest.getId();
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        String messageType = chatHistoryQueryRequest.getMessageType();
        String message = chatHistoryQueryRequest.getMessage();
        Long parentId = chatHistoryQueryRequest.getParentId();
        String sortField = chatHistoryQueryRequest.getSortField();
        String sortOrder = chatHistoryQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("app_id", appId)
                .eq("user_id", userId)
                .eq("message_type", messageType)
                .eq("parent_id", parentId)
                .like("message", message);

        // 3. 排序字段为空时，默认按创建时间降序（最新的消息在前）
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        } else {
            queryWrapper.orderBy("create_time", false);
        }
        return queryWrapper;
    }

    /**
     * 游标分页查询某个应用的对话历史（每次加载最新的 N 条，支持向前加载更多历史记录）
     *
     * @param appId                   应用id
     * @param pageSize                每次加载的条数
     * @param lastCreateTime          上一次查询的最后一条消息的创建时间，为空表示查询最新的消息
     * @param chatHistoryQueryRequest 其他查询条件
     *
     * @return 对话历史分页对象
     */
    @Override
    public Page<ChatHistory> listAppChatHistoryByPage(Long appId, long pageSize, LocalDateTime lastCreateTime, ChatHistoryQueryRequest chatHistoryQueryRequest) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "每次加载的条数不合法");
        // 2. 强制指定应用id，并按创建时间降序，保证应用之间的数据隔离
        ChatHistoryQueryRequest queryRequest = chatHistoryQueryRequest == null ? new ChatHistoryQueryRequest() : chatHistoryQueryRequest;
        queryRequest.setAppId(appId);
        queryRequest.setLastCreateTime(lastCreateTime);
        queryRequest.setSortField("create_time");
        queryRequest.setSortOrder("descend");
        QueryWrapper queryWrapper = getQueryWrapper(queryRequest);
        // 3. 游标查询：只查询比上一条更早的消息，实现「向前加载更多历史记录」
        if (lastCreateTime != null) {
            queryWrapper.lt("create_time", lastCreateTime);
        }
        // 4. 游标分页始终查询第一页
        return this.page(Page.of(1, pageSize), queryWrapper);
    }

    /**
     * 加载对话历史到记忆中（对话记忆初始化时调用，用于恢复历史上下文）
     *
     * @param appId      应用id
     * @param chatMemory 对话记忆
     * @param maxCount   最多加载的历史消息条数
     */
    @Override
    public int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount) {
        // 1. 校验参数
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不合法");
        ThrowUtils.throwIf(chatMemory == null, ErrorCode.PARAMS_ERROR, "对话记忆不能为空");
        ThrowUtils.throwIf(maxCount <= 0, ErrorCode.PARAMS_ERROR, "加载的历史消息条数不合法");
        try {
            // 2. 构造查询条件：只查询用户消息和AI消息，按创建时间降序，限制条数
            QueryWrapper queryWrapper = QueryWrapper.create()
                    .eq("app_id", appId)
                    .orderBy("create_time", false)
                    .limit(1, maxCount);
            // 3. 查询对话历史
            List<ChatHistory> chatHistoryList = this.list(queryWrapper);
            if (CollUtil.isEmpty(chatHistoryList)) {
                log.info("该应用暂无对话历史，appId: {}", appId);
                return 0;
            }
            // 4. 反转列表，按时间正序（旧的在前，新的在后）
            chatHistoryList = chatHistoryList.reversed();
            // 5. 将对话历史加载到记忆中
            int loadedCount = 0;
            // 先清理记忆中的历史消息，避免重复加载
            chatMemory.clear();
            for (ChatHistory chatHistory : chatHistoryList) {
                // 5.1 历史数据中可能存在旧版本落库的超长消息，加载进记忆前统一截断，避免撑爆模型上下文
                String message = TextUtils.truncate(chatHistory.getMessage(), ChatHistoryConstant.MEMORY_MESSAGE_MAX_LENGTH);
                // 5.2 过滤空消息，避免把无效内容塞进模型上下文
                if (StrUtil.isBlank(message)) {
                    continue;
                }
                // 5.3 根据消息类型，将消息转换为对应的 ChatMessage 对象，并添加到记忆中
                if (ChatMessageTypeEnum.USER.getValue().equals(chatHistory.getMessageType())) {
                    chatMemory.add(UserMessage.from(message));
                    loadedCount++;
                    // 5.4 AI 消息
                } else if (ChatMessageTypeEnum.AI.getValue().equals(chatHistory.getMessageType())) {
                    chatMemory.add(AiMessage.from(message));
                    loadedCount++;
                }
            }
            log.info("加载对话历史到记忆成功，appId: {}, 加载条数: {}", appId, loadedCount);
            return loadedCount;
        } catch (Exception e) {
            log.error("加载对话历史到记忆失败", e);
            return 0;
        }
    }
}
