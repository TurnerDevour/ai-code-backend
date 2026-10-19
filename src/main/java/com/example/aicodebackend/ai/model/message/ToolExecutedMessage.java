package com.example.aicodebackend.ai.model.message;

import dev.langchain4j.service.tool.ToolExecution;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果消息
 * 注意 {@code failed}：LangChain4j 对"工具执行被终止"的情况（参数不是合法 JSON、工具名不存在、
 * 工具内部抛异常）同样会回调 onToolExecuted（源码注释：Emit the "after" notification for……
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolExecutedMessage extends StreamMessage {

    private String id;

    private String name;

    private String arguments;

    private String result;

    /** 工具是否执行失败（参数不合法 / 工具名不存在 / 工具内部异常） */
    private boolean failed;

    /**
     * 展示文本（形如 {@code [🔧 工具调用] 修改文件内容 xx.vue} + 修改前后代码块）
     * 由后端按工具自己声明的展示逻辑生成，见 {@code ToolMessageRenderer}。
     * 前端不要按工具名猜参数结构：{@code modifyFile} 没有 content 字段……
     */
    private String display;

    public ToolExecutedMessage(ToolExecution toolExecution) {
        super(StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        this.id = toolExecution.request().id();
        this.name = toolExecution.request().name();
        this.arguments = toolExecution.request().arguments();
        this.result = toolExecution.result();
        this.failed = toolExecution.hasFailed();
    }
}
