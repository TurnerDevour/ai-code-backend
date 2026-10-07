package com.example.aicodebackend.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.Data;

/**
 * 对话历史
 */
@Data
@Table(value = "chat_history")
public class ChatHistory implements Serializable {
    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 消息
     */
    private String message;

    /**
     * AI 思考过程（推理模型的 reasoning_content）
     * <p>
     * 只有 AI 消息会有值；与 {@link #message} 分开存：思考过程是"模型怎么想的"，
     * 用于在对话页顶部单独展示，不进正文、也不参与模型上下文。
     */
    private String thinking;

    /**
     * user/ai
     */
    private String messageType;

    /**
     * 应用id
     */
    private Long appId;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 父消息id（用于上下文关联）
     */
    private Long parentId;

    /**
     * 是否删除
     */
    @Column(isLogicDelete = true)
    private Integer isDelete;

    @Serial
    private static final long serialVersionUID = 1L;
}
