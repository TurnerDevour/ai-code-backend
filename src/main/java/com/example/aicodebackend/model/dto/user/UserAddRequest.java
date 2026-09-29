package com.example.aicodebackend.model.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户添加请求体（管理员）
 */
@Data
@Schema(description = "用户添加请求体（管理员）")
public class UserAddRequest implements Serializable {

    /**
     * 用户昵称
     */
    @Schema(description = "用户昵称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    /**
     * 账号
     */
    @Schema(description = "账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userAccount;

    /**
     * 用户头像
     */
    @Schema(description = "用户头像")
    private String userAvatar;

    /**
     * 用户简介
     */
    @Schema(description = "用户简介")
    private String userProfile;

    /**
     * 用户角色: user, admin
     */
    @Schema(description = "用户角色: user, admin", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userRole;

    @Serial
    private static final long serialVersionUID = 1L;
}
