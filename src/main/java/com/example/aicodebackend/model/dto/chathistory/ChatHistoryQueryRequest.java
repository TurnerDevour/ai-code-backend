package com.example.aicodebackend.model.dto.chathistory;

import com.example.aicodebackend.common.PageRequest;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 对话历史查询请求对象
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "对话历史查询请求对象")
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    @Schema(description = "id")
    private Long id;

    /**
     * 应用id
     */
    @Schema(description = "应用id")
    private Long appId;

    /**
     * 创建用户id
     */
    @Schema(description = "创建用户id")
    private Long userId;

    /**
     * 消息类型：user/ai/error
     */
    @Schema(description = "消息类型：user/ai/error")
    private String messageType;

    /**
     * 消息内容（模糊查询）
     */
    @Schema(description = "消息内容（模糊查询）")
    private String message;

    /**
     * 父消息id（用于上下文关联）
     */
    @Schema(description = "父消息id（用于上下文关联）")
    private Long parentId;

    /**
     * 上一次查询的最后一条消息的创建时间（游标分页，用于向前加载更多历史记录）
     */
    @Schema(description = "上一次查询的最后一条消息的创建时间（游标分页，用于向前加载更多历史记录）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastCreateTime;

    @Serial
    private static final long serialVersionUID = 1L;
}
