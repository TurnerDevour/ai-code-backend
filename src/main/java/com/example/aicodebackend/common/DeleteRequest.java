package com.example.aicodebackend.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@Schema(description = "删除请求")
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    @NotNull(message = "id不能为空")
    @Schema(description = "id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Serial
    private static final long serialVersionUID = 1L;
}
