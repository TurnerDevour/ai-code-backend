package com.example.aicodebackend.model.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 应用更新请求对象（管理员）
 */
@Data
@Schema(description = "应用更新请求对象（管理员）")
public class AppAdminUpdateRequest implements Serializable {

    /**
     * id
     */
    @Schema(description = "id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    /**
     * 应用名称
     */
    @Schema(description = "应用名称")
    private String appName;

    /**
     * 应用封面
     */
    @Schema(description = "应用封面")
    private String cover;

    /**
     * 优先级（值为 99 表示精选应用）
     */
    @Schema(description = "优先级（值为 99 表示精选应用）")
    private Integer priority;

    @Serial
    private static final long serialVersionUID = 1L;
}
