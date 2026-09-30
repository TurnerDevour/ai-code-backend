package com.example.aicodebackend.model.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 应用创建请求体（用户，只需填写初始化提示词）
 */
@Data
@Schema(description = "应用创建请求体（用户，只需填写初始化提示词）")
public class AppAddRequest implements Serializable {

    /**
     * 应用初始化的 prompt
     */
    @Schema(description = "应用初始化的 prompt", requiredMode = Schema.RequiredMode.REQUIRED)
    private String initPrompt;

    @Serial
    private static final long serialVersionUID = 1L;
}
