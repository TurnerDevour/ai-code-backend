package com.example.aicodebackend.model.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 应用更新请求对象（用户，目前只支持修改应用名称）
 */
@Data
@Schema(description = "应用更新请求对象（用户，目前只支持修改应用名称）")
public class AppUpdateRequest implements Serializable {

    /**
     * id
     */
    @Schema(description = "id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    /**
     * 应用名称
     */
    @Schema(description = "应用名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appName;

    @Serial
    private static final long serialVersionUID = 1L;
}
