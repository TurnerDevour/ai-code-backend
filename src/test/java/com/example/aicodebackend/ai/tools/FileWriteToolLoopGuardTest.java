package com.example.aicodebackend.ai.tools;

import cn.hutool.core.io.FileUtil;
import com.example.aicodebackend.constant.AppConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件写入工具的"重复写入断路器"测试
 * <p>
 * 背景（实测死循环）：模型一旦以为文件没写成功，就会无限重复写入同一批文件。
 * 重复写入本身有确定答案（文件里已经是这份内容了），所以工具在这里必须把循环打破：
 * 连续第 3 次写入完全相同的内容时跳过写入，并明确提示模型继续别的文件或收尾。
 */
class FileWriteToolLoopGuardTest {

    /** 专用测试应用 id：避免与真实数据、以及其它用例的静态计数互相影响 */
    private static final long APP_ID_REPEAT = 990000000000000001L;
    private static final long APP_ID_CONTENT_CHANGED = 990000000000000002L;

    private final FileWriteTool fileWriteTool = new FileWriteTool();

    private Path projectDir;

    @BeforeEach
    void setUp() {
        projectDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID_REPEAT);
        FileUtil.del(projectDir.toFile());
        FileUtil.del(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID_CONTENT_CHANGED).toFile());
    }

    @AfterEach
    void tearDown() {
        FileUtil.del(projectDir.toFile());
        FileUtil.del(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + APP_ID_CONTENT_CHANGED).toFile());
    }

    /** 连续第 3 次写入完全相同的内容 → 跳过写入，并给出"别再重复了"的明确指引 */
    @Test
    void shouldSkipThirdIdenticalWriteAndTellModelToMoveOn() throws Exception {
        String content = "<template>\n  <div class=\"home\">你好</div>\n</template>\n";

        assertTrue(fileWriteTool.writeToFile("src/App.vue", content, APP_ID_REPEAT).contains("文件写入成功"),
                "第 1 次写入应当真的执行");
        assertTrue(fileWriteTool.writeToFile("src/App.vue", content, APP_ID_REPEAT).contains("文件写入成功"),
                "第 2 次写入（允许一次重试）仍应执行");

        String third = fileWriteTool.writeToFile("src/App.vue", content, APP_ID_REPEAT);

        assertTrue(third.contains("已跳过写入"), "第 3 次相同写入必须被跳过，实际返回：" + third);
        assertTrue(third.contains("不要重复写同一个文件"), "必须明确告诉模型别再重复写入，实际返回：" + third);
        // 磁盘内容不受影响：跳过的是一次无效写入
        assertEquals(content, Files.readString(projectDir.resolve("src/App.vue")));
    }

    /** 内容变了就重新计数：模型正常迭代修改同一个文件不能被拦 */
    @Test
    void shouldResetCounterWhenContentChanges() {
        String first = "<template>v1</template>";
        String second = "<template>v2</template>";

        assertTrue(fileWriteTool.writeToFile("src/App.vue", first, APP_ID_CONTENT_CHANGED).contains("文件写入成功"));
        assertTrue(fileWriteTool.writeToFile("src/App.vue", first, APP_ID_CONTENT_CHANGED).contains("文件写入成功"));
        assertTrue(fileWriteTool.writeToFile("src/App.vue", first, APP_ID_CONTENT_CHANGED).contains("已跳过写入"));

        // 内容变化后计数清零：接下来两次仍然可以正常写入
        assertTrue(fileWriteTool.writeToFile("src/App.vue", second, APP_ID_CONTENT_CHANGED).contains("文件写入成功"),
                "内容变化后必须重新计数");
        assertTrue(fileWriteTool.writeToFile("src/App.vue", second, APP_ID_CONTENT_CHANGED).contains("文件写入成功"));
    }

    /** 不同文件之间互不影响 */
    @Test
    void shouldTrackRepeatsPerFileContentOnly() throws Exception {
        String content = "<template>same</template>";

        assertTrue(fileWriteTool.writeToFile("src/A.vue", content, APP_ID_REPEAT).contains("文件写入成功"));
        assertTrue(fileWriteTool.writeToFile("src/B.vue", content, APP_ID_REPEAT).contains("文件写入成功"),
                "写的是另一个文件，不应被当成重复写入");
        assertTrue(Files.exists(new File(projectDir.toFile(), "src/A.vue").toPath()));
        assertTrue(Files.exists(new File(projectDir.toFile(), "src/B.vue").toPath()));
    }

    /** 越界路径必须被拒绝：不能借相对路径写到项目目录之外 */
    @Test
    void shouldRejectPathOutsideProject() {
        String result = fileWriteTool.writeToFile("../../evil.js", "x", APP_ID_REPEAT);

        assertTrue(result.contains("拒绝写入项目目录之外的路径"), "越界路径必须被拒绝，实际返回：" + result);
        assertFalse(Files.exists(projectDir.getParent().resolve("evil.js")), "越界文件不允许被创建");
    }

    /**
     * 非法路径不能把异常抛出工具
     * <p>
     * 以前只 catch IOException，InvalidPathException（RuntimeException）会逃出工具，
     * LangChain4j 只会把它变成一条"看不出失败"的工具结果——模型以为写成功、用户看到 AI 说改了但没写。
     */
    @Test
    void shouldReturnFailureInsteadOfThrowingForInvalidPath() {
        String result = fileWriteTool.writeToFile("src/bad\u0000name.js", "x", APP_ID_REPEAT);

        assertTrue(result.startsWith("写入文件失败"), "必须返回失败信息而不是抛异常，实际返回：" + result);
        assertTrue(result.contains("错误类型："), "失败信息里要带异常类型，便于排查，实际返回：" + result);
    }

    /** 内容为 null 也不能抛异常（模型偶尔会漏掉 content 字段） */
    @Test
    void shouldTolerateNullContent() {
        String result = fileWriteTool.writeToFile("src/Empty.vue", null, APP_ID_REPEAT);

        assertTrue(result.contains("文件写入成功"), "null 内容按空文件处理，实际返回：" + result);
        assertTrue(Files.exists(projectDir.resolve("src/Empty.vue")));
    }
}
