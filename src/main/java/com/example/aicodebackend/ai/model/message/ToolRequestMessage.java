package com.example.aicodebackend.ai.model.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具调用消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolRequestMessage extends StreamMessage {

    private String id;

    private String name;

    private String arguments;

    /**
     * 展示文本（形如 {@code [🔧 选择工具] 修改文件内容}）
     * <p>
     * 由后端按工具自己声明的展示文案生成，见 {@code ToolMessageRenderer}：
     * 前端不要按工具名猜文案，否则调 {@code modifyFile} 也会显示成"写入文件"。
     */
    private String display;

    public ToolRequestMessage(ToolExecutionRequest toolExecutionRequest) {
        super(StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        this.id = toolExecutionRequest.id();
        this.name = toolExecutionRequest.name();
        this.arguments = toolExecutionRequest.arguments();
    }
}
