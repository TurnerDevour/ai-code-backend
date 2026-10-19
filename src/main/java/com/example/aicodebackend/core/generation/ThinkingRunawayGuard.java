package com.example.aicodebackend.core.generation;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 「思考跑飞」判定器
 * 背景（实测）：思考模式的模型遇到"当前能力做不到"的需求时（例如"上网找真实的历史人物画像"，
 * 而应用只有文件读写工具），会在内部推理里反复推翻自己——既不出正文、也不调用工具……
 */
public class ThinkingRunawayGuard {

    /**
     * 推导字符上限时，对 1 个 token 能产出多少字符的保守上界
     * 中文约 1.5 字符/token、英文约 4 字符/token，代码与混合内容取英文口径，宁大勿小。
     */
    static final int CHARS_PER_TOKEN_UPPER_BOUND = 4;

    /**
     * 推导阈值时留的安全系数
     * 平台预算只是"推理 token"的上限，实际输出还会带上换行、代码块等，且平台对预算的执行并不严格，
     * 因此按上界推导之后再放宽一半，确保"预算内的合法推理"绝不可能被判跑飞。
     */
    static final double SAFETY_FACTOR = 1.5;

    /**
     * 推导时长上限时假设的最慢推理吞吐（token/秒）
     * 平台繁忙时实测会掉到这个量级。用最慢吞吐而不是平均吞吐，是为了让时长上限覆盖
     * "预算跑满 + 平台很慢"这一最坏但合法的情形。
     */
    static final double SLOWEST_TOKENS_PER_SECOND = 6;

    /**
     * 时长上限的下限
     * 预算配得很小（例如 2048）不代表一定很快：慢链路上 2048 token 也可能要好几分钟。
     */
    static final Duration MIN_MAX_THINKING_DURATION = Duration.ofMinutes(10);

    /**
     * 平台没有配置思考预算时用的兜底字符上限
     * 此时没有任何第一道防线，守卫就是唯一的闸门：给足空间（约等于 1024 token 预算的推导值），
     * 跑飞的一轮仍会在可以接受的时间内被切掉。
     */
    public static final int FALLBACK_MAX_THINKING_CHARS = 60_000;

    /** 平台没有配置思考预算时用的兜底时长上限 */
    public static final Duration FALLBACK_MAX_THINKING_DURATION = Duration.ofMinutes(10);

    private final Limits limits;

    private final LongSupplier nanoTime;

    private int thinkingChars;

    /** 本次无进展窗口的起点；{@code -1} 表示"这一轮还没有收到任何思考增量"，此时不计时 */
    private long windowStartNanos = NOT_STARTED;

    private static final long NOT_STARTED = -1L;

    /** 阈值 */
    public record Limits(int maxThinkingChars, Duration maxThinkingDuration) {

        /**
         * 按所选模型的思考预算推导阈值（守卫只做第二道防线，因此必须比预算宽松）
         * 为 {@code null} 或非正数表示没配预算，用兜底阈值
         */
        public static Limits forThinkingBudget(Integer thinkingBudgetTokens) {
            if (thinkingBudgetTokens == null || thinkingBudgetTokens <= 0) {
                return new Limits(FALLBACK_MAX_THINKING_CHARS, FALLBACK_MAX_THINKING_DURATION);
            }
            int maxChars = (int) Math.ceil(thinkingBudgetTokens
                    * (double) CHARS_PER_TOKEN_UPPER_BOUND * SAFETY_FACTOR);
            Duration maxDuration = Duration.ofSeconds(
                    (long) Math.ceil(thinkingBudgetTokens / SLOWEST_TOKENS_PER_SECOND));
            if (maxDuration.compareTo(MIN_MAX_THINKING_DURATION) < 0) {
                maxDuration = MIN_MAX_THINKING_DURATION;
            }
            return new Limits(maxChars, maxDuration);
        }

        /** 用配置里的显式阈值覆盖推导结果（{@code null} 表示不覆盖该项） */
        public Limits withOverrides(Integer overrideChars, Duration overrideDuration) {
            if (overrideChars == null && overrideDuration == null) {
                return this;
            }
            int chars = overrideChars == null ? maxThinkingChars : overrideChars;
            Duration duration = overrideDuration == null ? maxThinkingDuration : overrideDuration;
            return new Limits(chars, duration);
        }
    }

    /** 使用"没配思考预算"的兜底阈值 */
    public ThinkingRunawayGuard() {
        this(Limits.forThinkingBudget(null));
    }

    public ThinkingRunawayGuard(Limits limits) {
        this(limits, System::nanoTime);
    }

    /** 供测试注入时钟的构造器 */
    ThinkingRunawayGuard(Limits limits, LongSupplier nanoTime) {
        this.limits = limits;
        this.nanoTime = nanoTime;
    }

    /**
     * 收到一段思考增量
     * 首个增量会启动本次无进展窗口的计时：在此之前（建连、排队、大提示词预填充）的时间
     * 不属于"模型在思考"，不能算进无进展思考时长。
     */
    public void onThinking(String thinking) {
        if (thinking == null) {
            return;
        }
        if (windowStartNanos == NOT_STARTED) {
            windowStartNanos = nanoTime.getAsLong();
        }
        thinkingChars += thinking.length();
    }

    /**
     * 出现"进展"（输出正文 / 调用工具）：重新开始计数
     * 窗口起点重新置为"未开始"，由下一次思考增量重新启动计时。这样"两次进展之间的空档"
     * （例如工具执行、正文下发的间隔）也不会被算成无进展思考。
     */
    public void onProgress() {
        thinkingChars = 0;
        windowStartNanos = NOT_STARTED;
    }

    /**
     * 是否已判定跑飞
     * 必须有思考增量才可能判定（{@code thinkingChars > 0}）：一个字节都没收到属于"卡住"，
     * 由读超时负责，不该被误报成"思考跑飞"。
     */
    public boolean isRunaway() {
        if (thinkingChars <= 0) {
            return false;
        }
        return thinkingChars > limits.maxThinkingChars()
                || elapsedSeconds() > limits.maxThinkingDuration().toSeconds();
    }

    /** 当前生效的阈值（日志与排查用） */
    public Limits limits() {
        return limits;
    }

    /** 无进展期间已累积的思考字符数 */
    public int thinkingChars() {
        return thinkingChars;
    }

    /** 无进展期间已过去的秒数（尚未收到思考增量时为 0） */
    public long elapsedSeconds() {
        if (windowStartNanos == NOT_STARTED) {
            return 0;
        }
        return (nanoTime.getAsLong() - windowStartNanos) / 1_000_000_000L;
    }

    /** 供日志使用的现状描述：带上触发维度与阈值，便于判断是"真跑飞"还是"阈值不够宽" */
    public String describe() {
        return "无进展思考 " + thinkingChars + " 字 / " + elapsedSeconds() + " 秒"
                + "（上限 " + limits.maxThinkingChars() + " 字 / "
                + limits.maxThinkingDuration().toSeconds() + " 秒；"
                + (thinkingChars > limits.maxThinkingChars() ? "字符数超限" : "时长超限") + "）";
    }
}
