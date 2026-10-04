package com.example.aicodebackend.model.dto.app;

import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
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

    /**
     * 代码生成类型（枚举），不传时默认使用原生多文件模式
     */
    @Schema(description = "代码生成类型（枚举）：html / multi_file / vue_project，默认 multi_file")
    private String codeGenType = CodeGenTypeEnum.MULTI_FILE.getValue();

    /**
     * AI 模型类型（枚举），不传时默认使用 deepseek-flash
     */
    @Schema(description = "AI 模型类型（枚举）：deepseek-flash / deepseek-v4-pro，默认 deepseek-flash")
    private String aiModelType = AIModelTypeEnum.DEEPSEEK_FLASH.getValue();

    @Serial
    private static final long serialVersionUID = 1L;
}
