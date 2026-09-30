package com.example.aicodebackend.model.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@Schema(description = "应用部署请求体")
public class AppDeployRequest implements Serializable {

    /**
     * 应用 id
     */
    @Schema(description = "应用 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long appId;

    @Serial
    private static final long serialVersionUID = 1L;
}
