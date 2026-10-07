package com.example.aicodebackend.ai.model.message;

import dev.langchain4j.service.tool.ToolExecution;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果消息
 * <p>
 * 注意 {@code failed}：LangChain4j 对"工具执行被终止"的情况（参数不是合法 JSON、工具名不存在、
 * 工具内部抛异常）<b>同样会回调 onToolExecuted</b>（源码注释：Emit the "after" notification for
 * every terminated execution - success OR failure）。也就是说：<b>工具没执行成功也会产生一条
 * 「工具调用」消息</b>。如果不把失败标记透出去，前端与对话历史里"写了文件"和"根本没写"
 * 会长得一模一样——用户看到 AI 说改好了，页面却没变（实测问题）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolExecutedMessage extends StreamMessage {

    private String id;

    private String name;

    private String arguments;

    private String result;

    /**
     * 工具是否执行失败（参数不合法 / 工具名不存在 / 工具内部异常）
     */
    private boolean failed;

    public ToolExecutedMessage(ToolExecution toolExecution) {
        super(StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        this.id = toolExecution.request().id();
        this.name = toolExecution.request().name();
        this.arguments = toolExecution.request().arguments();
        this.result = toolExecution.result();
        this.failed = toolExecution.hasFailed();
    }
}
