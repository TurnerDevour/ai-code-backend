package com.example.aicodebackend.constant;

public interface ChatHistoryConstant {

    /**
     * 每次加载的对话历史条数（类似聊天软件的消息加载机制，每次加载最新 10 条）
     */
    long DEFAULT_PAGE_SIZE = 10;

    /**
     * 单次最多加载的对话历史条数
     */
    long MAX_PAGE_SIZE = 20;

    /**
     * 对话记忆中最多保留的消息条数（同时也是对话记忆初始化时最多加载的历史消息条数）
     */
    int MEMORY_MAX_MESSAGES = 50;

    /**
     * 落库时单个文件内容保留的最大字符数
     * <p>
     * 工具每写入一个文件都会产生一条消息，AI 消息里只保留「文件路径 + 内容摘要」，
     * 避免完整工程源码落库（单条消息可达数十万字符）并参与后续多轮上下文。
     */
    int FILE_CONTENT_MAX_LENGTH = 800;

    /**
     * 单条非用户消息（AI 消息、错误消息）落库的最大字符数
     */
    int MESSAGE_MAX_LENGTH = 5000;

    /**
     * 加载对话记忆时单条消息保留的最大字符数
     * <p>
     * 兜底限制：历史数据中可能存在旧版本落库的超长消息（例如完整工程源码），加载进模型上下文前统一截断。
     * 取值与 {@link #MESSAGE_MAX_LENGTH} 保持一致，避免新落库的消息在加载时被二次截断。
     */
    int MEMORY_MESSAGE_MAX_LENGTH = 5000;
}
