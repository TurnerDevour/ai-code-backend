package com.example.aicodebackend.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 文本工具类
 * <p>
 * 主要用于限制写入数据库、进入对话记忆的文本长度，
 * 避免 AI 生成的完整工程源码撑爆存储和模型上下文。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 按最大长度截断文本，超长部分被丢弃并追加截断说明
     *
     * @param text      原始文本
     * @param maxLength 允许保留的最大字符数（不包含截断说明本身）
     *
     * @return 截断后的文本；未超长或无需截断时原样返回
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return StrUtil.subPre(text, maxLength)
                + "\n\n...（内容过长，已省略 " + (text.length() - maxLength) + " 字符，原文共 " + text.length() + " 字符）";
    }
}
