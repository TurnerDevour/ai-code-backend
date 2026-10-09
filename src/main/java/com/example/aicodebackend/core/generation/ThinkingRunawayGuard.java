package com.example.aicodebackend.core.generation;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 「思考跑飞」判定器
 * <p>
 * 背景（实测）：思考模式的模型遇到"当前能力做不到"的需求时（例如"上网找真实的历史人物画像"，
 * 而应用只有文件读写工具），会在内部推理里反复推翻自己——既不出正文、也不调用工具，
 * 只是一直吐 {@code reasoning_content}。前端「AI 思考过程」面板因此看起来是死循环：
 * 实测同一轮已跑 15 分钟以上仍未结束，而平台侧的读超时不会触发（数据一直在流动，不是"卡住"）。
 * <p>
 * 判定只看一件事：<b>距离上一次"有进展"（输出正文或调用工具）过去了多少</b>。两个维度任一超限即判跑飞：
 * <ul>
 *     <li>无进展思考字符数超过 {@value #MAX_THINKING_CHARS}；</li>
 *     <li>无进展思考时长超过 {@value #MAX_THINKING_MINUTES} 分钟。</li>
 * </ul>
 * 阈值刻意放得比正常生成宽（实测正常一轮的推理约 4000~6000 字、几十秒），
 * 只有"明显停不下来"才会被判跑飞，不会误伤复杂的正常任务。
 * <p>
 * 这是 {@code thinking-budget}（平台侧对单次推理的 token 上限）之外的第二道防线：
 * 预算靠平台执行，遇到不支持该参数的模型/平台时，这里仍能把跑飞的一轮掐掉。
 */
public class ThinkingRunawayGuard {

    /**
     * 无进展思考的字符数上限
     */
    public static final int MAX_THINKING_CHARS = 20_000;

    /**
     * 无进展思考的时长上限（分钟）
     * <p>
     * 放到 5 分钟：正常一轮"重思考"（Vue 工程首次生成）实测约 3~4 分钟仍在预算内，
     * 阈值必须比它宽，才不会被误杀；而跑飞的那种是"永不收敛"，5 分钟足以把它切掉。
     */
    public static final int MAX_THINKING_MINUTES = 5;

    private final LongSupplier nanoTime;

    private int thinkingChars;

    private long windowStartNanos;

    public ThinkingRunawayGuard() {
        this(System::nanoTime);
    }

    /**
     * 供测试注入时钟的构造器
     *
     * @param nanoTime 单调时钟（纳秒）
     */
    ThinkingRunawayGuard(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.windowStartNanos = nanoTime.getAsLong();
    }

    /**
     * 收到一段思考增量
     *
     * @param thinking 思考增量（可为 null）
     */
    public void onThinking(String thinking) {
        if (thinking != null) {
            thinkingChars += thinking.length();
        }
    }

    /**
     * 出现"进展"（输出正文 / 调用工具）：重新开始计时与计数
     */
    public void onProgress() {
        thinkingChars = 0;
        windowStartNanos = nanoTime.getAsLong();
    }

    /**
     * 是否已判定跑飞
     * <p>
     * 必须有思考增量才可能判定（{@code thinkingChars > 0}）：一个字节都没收到属于"卡住"，
     * 由读超时负责，不该被误报成"思考跑飞"。
     *
     * @return true 表示这一轮已经停不下来
     */
    public boolean isRunaway() {
        if (thinkingChars <= 0) {
            return false;
        }
        return thinkingChars > MAX_THINKING_CHARS
                || elapsedSeconds() > Duration.ofMinutes(MAX_THINKING_MINUTES).toSeconds();
    }

    /**
     * 无进展期间已累积的思考字符数
     */
    public int thinkingChars() {
        return thinkingChars;
    }

    /**
     * 无进展期间已过去的秒数
     */
    public long elapsedSeconds() {
        return (nanoTime.getAsLong() - windowStartNanos) / 1_000_000_000L;
    }

    /**
     * 供日志使用的现状描述
     */
    public String describe() {
        return "无进展思考 " + thinkingChars + " 字 / " + elapsedSeconds() + " 秒";
    }
}
