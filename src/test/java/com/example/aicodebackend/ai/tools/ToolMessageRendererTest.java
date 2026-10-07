package com.example.aicodebackend.ai.tools;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具展示文本生成的回归测试
 * <p>
 * 背景（实测问题）：实时 SSE 只下发 {@code name / arguments / result} 三个原始字段，
 * 前端只能自己"猜"每个工具的参数结构，于是把 {@code modifyFile} 也当成 {@code writeToFile} 渲染：
 * 界面显示「写入文件 src/pages/HomePage.vue」配一个<b>空代码块</b>（modifyFile 的参数是
 * oldContent / newContent，根本没有 content），用户看到的就是"AI 调用工具写文件输出空白"，
 * 而文件其实已经被正确修改（预览正常）。
 * <p>
 * 现在展示文本由工具自己声明、随消息下发，这里锁住每个工具的展示形态。
 */
class ToolMessageRendererTest {

    private final ToolMessageRenderer renderer = createRenderer();

    /**
     * 容器外手工装配工具管理器（工具本身是无状态的，直接 new 即可）
     */
    private static ToolMessageRenderer createRenderer() {
        ToolManager toolManager = new ToolManager();
        ReflectionTestUtils.setField(toolManager, "tools", new BaseTool[]{
                new FileWriteTool(), new FileModifyTool(), new FileDeleteTool(),
                new FileReadTool(), new FileDirReadTool()});
        toolManager.initTools();
        return new ToolMessageRenderer(toolManager);
    }

    /**
     * 修改文件：必须给出"修改前 / 修改后"的内容
     * <p>
     * 这正是本次修复的线上问题：按 writeToFile 渲染会得到一个空代码块。
     */
    @Test
    @DisplayName("修改文件：展示修改前后内容，不能渲染成空代码块")
    void modifyFileShouldRenderOldAndNewContent() {
        String arguments = JSONUtil.createObj()
                .set("relativeFilePath", "src/pages/HomePage.vue")
                .set("oldContent", "import { store, TAGS } from '@/utils/todoStore'")
                .set("newContent", "import { store } from '@/utils/todoStore'")
                .toString();

        String display = renderer.renderExecuted("modifyFile", arguments, false, "文件修改成功: src/pages/HomePage.vue");

        assertTrue(display.contains("修改文件内容 src/pages/HomePage.vue"), "应展示工具自己的中文名与文件路径: " + display);
        assertTrue(display.contains("修改前内容"), "应展示修改前内容: " + display);
        assertTrue(display.contains("import { store, TAGS }"), "修改前内容要完整: " + display);
        assertTrue(display.contains("修改后内容"), "应展示修改后内容: " + display);
        assertTrue(display.contains("import { store } from"), "修改后内容要完整: " + display);
    }

    /** 写入文件：展示文件路径与完整内容 */
    @Test
    @DisplayName("写入文件：展示路径与完整内容")
    void writeFileShouldRenderPathAndContent() {
        String arguments = JSONUtil.createObj()
                .set("relativeFilePath", "src/App.vue")
                .set("content", "<template><div>你好</div></template>")
                .toString();

        String display = renderer.renderExecuted("writeToFile", arguments, false, "文件写入成功，文件路径：src/App.vue");

        assertTrue(display.contains("写入文件内容 src/App.vue"), display);
        assertTrue(display.contains("<template><div>你好</div></template>"), display);
    }

    /**
     * "选择工具"阶段也要用工具自己的中文名
     * <p>
     * 原来是前端硬编码的「[🔧 选择工具] 写入文件」：调 modifyFile / deleteFile 时文案全是错的。
     */
    @Test
    @DisplayName("选择工具：使用工具自己声明的中文名")
    void requestShouldUseToolDisplayName() {
        assertEquals("[🔧 选择工具] 写入文件内容", renderer.renderRequest("writeToFile"));
        assertEquals("[🔧 选择工具] 修改文件内容", renderer.renderRequest("modifyFile"));
        assertEquals("[🔧 选择工具] 删除文件", renderer.renderRequest("deleteFile"));
    }

    /** 执行失败：必须显式标记，不能渲染成"写入了文件" */
    @Test
    @DisplayName("工具执行失败：显式标记失败并带上原因")
    void failedExecutionShouldBeMarked() {
        String display = renderer.renderExecuted("writeToFile", "{}", true,
                "Argument parsing failed: Unterminated string at position 223");

        assertTrue(display.contains("⚠️ [工具调用失败]"), display);
        assertTrue(display.contains("Unterminated string"), display);
    }

    /** 未注册的工具：返回空串（调用方跳过展示） */
    @Test
    @DisplayName("未注册的工具：不产生展示内容")
    void unknownToolShouldReturnEmpty() {
        assertEquals("", renderer.renderRequest("notExistTool"));
        assertEquals("", renderer.renderExecuted("notExistTool", "{}", false, "ok"));
    }

    /** 参数不是合法 JSON：返回空串，而不是抛异常把生成链路打断 */
    @Test
    @DisplayName("参数非法：返回空串且不抛异常")
    void invalidArgumentsShouldReturnEmpty() {
        assertEquals("", renderer.renderExecuted("writeToFile", "{不是 JSON", false, "ok"));
        assertEquals("", renderer.renderExecuted("writeToFile", "", false, "ok"));
    }
}
