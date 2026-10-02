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
}
