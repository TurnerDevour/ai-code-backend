package com.example.aicodebackend.core.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「思考跑飞」判定的回归测试
 * <p>
 * 背景（实测）：模型遇到做不到的需求时会一直吐 reasoning_content，既不出正文也不调用工具，
 * 前端「AI 思考过程」面板看起来就是死循环（实测同一轮跑了 15 分钟以上）。
 * 这里锁住判定规则本身，避免阈值被改坏成"正常生成也被误杀"或"跑飞也放过去"。
 * <p>
 * 其中"阈值必须比思考预算宽松"与"首 token 之前的等待不算思考时间"两条，
 * 对应线上真实误报：{@code thinking-budget=8192} 配写死的 5 分钟上限，
 * 预算内的合法推理被当成跑飞掐掉，用户看到的是"模型在思考阶段长时间没有产出"。
 */
class ThinkingRunawayGuardTest {

    @Test
    @DisplayName("无进展思考超过字符上限：判跑飞")
    void shouldDetectRunawayByCharacters() {
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard();

        guard.onThinking("想".repeat(ThinkingRunawayGuard.FALLBACK_MAX_THINKING_CHARS));
        assertFalse(guard.isRunaway(), "刚好到上限不应判跑飞");

        guard.onThinking("再想一点");
        assertTrue(guard.isRunaway(), "超过字符上限必须判跑飞");
    }

    @Test
    @DisplayName("无进展思考超过时长上限：判跑飞")
    void shouldDetectRunawayByElapsedTime() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(
                ThinkingRunawayGuard.Limits.forThinkingBudget(null), clock::get);
        guard.onThinking("我先想想");

        clock.addAndGet(ThinkingRunawayGuard.FALLBACK_MAX_THINKING_DURATION.toNanos());
        assertFalse(guard.isRunaway(), "刚好到时长上限不应判跑飞");

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(guard.isRunaway(), "超过时长上限必须判跑飞");
    }

    @Test
    @DisplayName("有进展（输出正文/调用工具）后重新计时，长任务不能被误杀")
    void progressShouldResetTheWindow() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(
                ThinkingRunawayGuard.Limits.forThinkingBudget(8192), clock::get);

        // 第一轮：Vue 工程首次生成那种"重思考"，仍在 8192 预算的推导阈值内
        clock.addAndGet(Duration.ofMinutes(12).toNanos());
        guard.onThinking("想".repeat(40_000));
        assertFalse(guard.isRunaway(), "预算内的长思考不能被判跑飞：" + guard.describe());
        guard.onProgress();

        // 进展之后的空档（工具执行、正文下发）同样不该计入无进展思考
        clock.addAndGet(Duration.ofMinutes(12).toNanos());
        assertEquals(0, guard.elapsedSeconds(), "进展之后的空档不该计入无进展思考");

        // 第二轮：又思考很久
        guard.onThinking("想".repeat(10_000));
        assertFalse(guard.isRunaway(), "每次有进展都应重新开始计数，复杂任务不能被误判成跑飞");
        assertTrue(guard.describe().contains("10000"), "现状描述要能用于日志定位：" + guard.describe());
    }

    @Test
    @DisplayName("根本没收到思考内容时不算跑飞：那属于卡住，由读超时负责")
    void silenceIsNotRunaway() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(
                ThinkingRunawayGuard.Limits.forThinkingBudget(8192), clock::get);

        clock.addAndGet(Duration.ofHours(1).toNanos());
        assertFalse(guard.isRunaway(), "一个字都没收到时不应报'思考跑飞'");
        assertEquals(0, guard.elapsedSeconds(), "没收到思考内容时不该有任何无进展思考时长");
    }

    @Test
    @DisplayName("阈值必须由思考预算推导，且比预算本身宽松（线上误报的回归）")
    void limitsMustBeLooserThanTheThinkingBudget() {
        int budget = 8192;
        ThinkingRunawayGuard.Limits limits = ThinkingRunawayGuard.Limits.forThinkingBudget(budget);

        // 预算内的推理最多产出 budget × 4 个字符（英文/代码口径）：阈值必须在这之上，
        // 否则平台允许的推理会被自己的守卫掐掉
        assertTrue(limits.maxThinkingChars() > budget * 4,
                "字符上限不能小于预算本身能产出的内容：" + limits);
        // 预算跑满所需的合法时间可能远超原来写死的 5 分钟（推理模型吞吐约 10~60 token/秒）
        assertTrue(limits.maxThinkingDuration().compareTo(Duration.ofMinutes(5)) > 0,
                "时长上限必须比原来写死的 5 分钟宽：" + limits);

        // 没配预算时仍有兜底阈值：此时没有平台侧的第一道防线，守卫不能失去上限
        ThinkingRunawayGuard.Limits fallback = ThinkingRunawayGuard.Limits.forThinkingBudget(null);
        assertTrue(fallback.maxThinkingChars() > 0, "兜底字符上限必须有值");
        assertTrue(fallback.maxThinkingDuration().toMinutes() > 0, "兜底时长上限必须有值");
    }

    @Test
    @DisplayName("首 token 之前的等待（建连/排队/预填充）不算无进展思考")
    void waitingForTheFirstTokenIsNotThinkingTime() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(
                ThinkingRunawayGuard.Limits.forThinkingBudget(8192), clock::get);

        // 建连、排队、大提示词预填充：9 分钟后才吐出第一个思考增量
        clock.addAndGet(Duration.ofMinutes(9).toNanos());
        guard.onThinking("开始推理");

        assertEquals(0, guard.elapsedSeconds(), "计时必须从首个思考增量开始，而不是从对象创建开始");
        assertFalse(guard.isRunaway(), "首 token 延迟不该被算成无进展思考");

        // 但真正的长时间无进展思考仍要判跑飞：模型一直在吐推理却不产出正文/工具调用
        clock.addAndGet(guard.limits().maxThinkingDuration().plusSeconds(1).toNanos());
        assertTrue(guard.isRunaway(), "真正的长时间无进展思考仍必须判跑飞");
    }

    @Test
    @DisplayName("配置里的显式阈值优先于预算推导（需要收紧时的手工口子）")
    void explicitOverridesWinOverTheDerivation() {
        ThinkingGuardProperties properties = new ThinkingGuardProperties();
        properties.setMaxDuration(Duration.ofMinutes(5));

        ThinkingRunawayGuard.Limits limits = properties.limitsFor(8192);
        assertEquals(Duration.ofMinutes(5), limits.maxThinkingDuration(), "显式覆盖必须生效");
        assertEquals(ThinkingRunawayGuard.Limits.forThinkingBudget(8192).maxThinkingChars(),
                limits.maxThinkingChars(), "只覆盖一项时，另一项仍按预算推导");

        properties.setMaxChars(1_000);
        properties.setMaxDuration(null);
        assertEquals(1_000, properties.limitsFor(8192).maxThinkingChars(), "字符上限覆盖也要生效");
    }
}
