package com.example.aicodebackend.service;

import com.example.aicodebackend.model.dto.chathistory.ChatHistoryQueryRequest;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;

public interface ChatHistoryService extends IService<ChatHistory> {

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
    Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType);

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
    Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType, Long parentId);

    /**
     * 删除某个应用的所有对话历史（删除应用时关联删除）
     *
     * @param appId 应用id
     *
     * @return 是否删除成功
     */
    boolean deleteByAppId(Long appId);

    /**
     * 获取查询条件包装器
     *
     * @param chatHistoryQueryRequest 对话历史查询请求对象
     *
     * @return 查询条件包装器
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

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
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, long pageSize, LocalDateTime lastCreateTime, ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 将某个应用的对话历史加载到内存中（用于 AI 记忆上下文）
     *
     * @param appId      应用id
     * @param chatMemory 对话记忆对象
     * @param maxCount   最大加载条数
     */
    int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount);
}
