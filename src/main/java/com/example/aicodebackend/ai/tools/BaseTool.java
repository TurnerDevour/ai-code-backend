package com.example.aicodebackend.ai.tools;

import cn.hutool.json.JSONObject;

/**
 * 工具基类
 * 定义所有工具的通用接口
 * <p>
 * 约定：工具只负责生成自己的展示内容，消息之间的空行等流式格式由
 * {@code JsonMessageStreamHandler} 统一包装。
 */
public abstract class BaseTool {

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的展示内容（展示给用户）
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("[🔧 选择工具] %s", getDisplayName());
    }

    /**
     * 生成工具执行结果的展示内容（推送给前端，同时保存到对话历史）
     *
     * @param arguments 工具执行参数
     *
     * @return 格式化的工具执行结果，无需展示时返回空字符串
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);
}
