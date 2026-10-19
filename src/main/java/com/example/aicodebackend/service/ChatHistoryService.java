package com.example.aicodebackend.service;

import com.example.aicodebackend.model.dto.chathistory.ChatHistoryQueryRequest;
import com.example.aicodebackend.model.entity.ChatHistory;
import com.example.aicodebackend.model.enums.ChatMessageTypeEnum;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatHistoryService extends IService<ChatHistory> {

    /** 添加对话消息（用户消息、AI 消息、错误消息都通过该方法持久化） */
    Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType);

    /** 添加对话消息，并关联父消息（用于上下文关联） */
    Long addChatMessage(Long appId, Long userId, String message, ChatMessageTypeEnum messageType, Long parentId);

    /**
     * 添加一条 AI 消息，同时记录模型的思考过程
     * 思考过程单独一列（{@code chat_history.thinking}）：它属于"模型怎么想的"，
     * 与给用户看的正文分开存放，前端在对话页顶部的「AI 思考过程」面板里单独展示……
     */
    Long addAiChatMessage(Long appId, Long userId, String message, String thinking);

    /** 删除某个应用的所有对话历史（删除应用时关联删除） */
    boolean deleteByAppId(Long appId);

    /** 获取查询条件包装器 */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /** 游标分页查询某个应用的对话历史（每次加载最新的 N 条，支持向前加载更多历史记录） */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, long pageSize, LocalDateTime lastCreateTime, ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 统计某个应用已有的 AI 消息条数
     * 生成任务注册表用它判断"这一轮生成是否已经落库"，避免重连时把同一条消息再落一次。
     */
    int countAiMessages(Long appId);

    /**
     * 读取某个应用最近的对话历史（按创建时间升序，旧的在前）
     * 只返回数据库里的原始记录，不做任何模型上下文相关的加工：把用户消息原样、
     * AI 消息压缩代码块后转成模型消息，是 ai 层的事（见……
     */
    List<ChatHistory> loadRecentChatHistory(Long appId, int maxCount);
}
