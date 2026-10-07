package com.example.aicodebackend.service.impl;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式消息 -> 对话历史内容的转换规则
 * <p>
 * 背景（实测问题）：LangChain4j 对"工具执行被终止"的情况同样会回调 onToolExecuted
 * （参数不是合法 JSON、工具名不存在、工具内部抛异常），此时<b>文件并没有写入</b>。
 * 如果历史里不做区分，"写了文件"和"根本没写"会长得一模一样：
 * 用户看到的是 AI 说改好了，页面却没有任何变化，而且完全无从排查。
 */
class AppServiceImplHistoryContentTest {

    private final AppServiceImpl appService = new AppServiceImpl();

    private String extract(String messageJson) throws Exception {
        Method method = AppServiceImpl.class.getDeclaredMethod("extractHistoryContent", String.class);
        method.setAccessible(true);
        return (String) method.invoke(appService, messageJson);
    }

    private String toolExecuted(boolean failed, String result) {
        return JSONUtil.createObj()
                .set("type", "tool_executed")
                .set("name", "writeToFile")
                .set("arguments", "{\"relativeFilePath\":\"src/App.vue\",\"content\":\"<template/>\"}")
                .set("result", result)
                .set("failed", failed)
                .toString();
    }

    /** 成功的工具调用：保持原有格式（工具名 + 完整入参） */
    @Test
    @DisplayName("工具调用成功：历史里记录工具名与入参")
    void successfulToolCallShouldBeRecordedAsCall() throws Exception {
        String content = extract(toolExecuted(false, "文件写入成功，文件路径：src/App.vue"));

        assertTrue(content.contains("[工具调用] writeToFile"), "应记录工具名，实际: " + content);
        assertFalse(content.contains("工具调用失败"), "成功不应标记为失败");
    }

    /** 失败的工具调用：必须显眼地标记出来，并带上失败原因 */
    @Test
    @DisplayName("工具调用失败：历史里必须显式标记并带失败原因")
    void failedToolCallShouldBeMarkedClearly() throws Exception {
        String content = extract(toolExecuted(true,
                "Argument parsing failed: Unterminated string at position 223"));

        assertTrue(content.contains("⚠️ [工具调用失败]"), "必须显式标记失败，实际: " + content);
        assertTrue(content.contains("Unterminated string"), "必须带上失败原因，实际: " + content);
        assertTrue(content.contains("writeToFile"), "仍需保留工具名，便于定位是哪个文件没写成功");
    }

    /** AI 文本增量照旧透传 */
    @Test
    @DisplayName("AI 文本增量原样累积")
    void aiResponseShouldBeAccumulatedVerbatim() throws Exception {
        String json = JSONUtil.createObj().set("type", "ai_response").set("data", "正在生成首页").toString();

        assertTrue(extract(json).contains("正在生成首页"));
    }
}
