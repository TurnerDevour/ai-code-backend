package com.example.aicodebackend.core.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「思考跑飞」判定的回归测试
 * <p>
 * 背景（实测）：模型遇到做不到的需求时会一直吐 reasoning_content，既不出正文也不调用工具，
 * 前端「AI 思考过程」面板看起来就是死循环（实测同一轮跑了 15 分钟以上）。
 * 这里锁住判定规则本身，避免阈值被改坏成"正常生成也被误杀"或"跑飞也放过去"。
 */
class ThinkingRunawayGuardTest {

    @Test
    @DisplayName("无进展思考超过字符上限：判跑飞")
    void shouldDetectRunawayByCharacters() {
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard();

        guard.onThinking("想".repeat(ThinkingRunawayGuard.MAX_THINKING_CHARS));
        assertFalse(guard.isRunaway(), "刚好到上限不应判跑飞");

        guard.onThinking("再想一点");
        assertTrue(guard.isRunaway(), "超过字符上限必须判跑飞");
    }

    @Test
    @DisplayName("无进展思考超过时长上限：判跑飞")
    void shouldDetectRunawayByElapsedTime() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(clock::get);
        guard.onThinking("我先想想");

        clock.addAndGet(java.time.Duration.ofMinutes(ThinkingRunawayGuard.MAX_THINKING_MINUTES).toNanos());
        assertFalse(guard.isRunaway(), "刚好到时长上限不应判跑飞");

        clock.addAndGet(java.time.Duration.ofSeconds(1).toNanos());
        assertTrue(guard.isRunaway(), "超过时长上限必须判跑飞");
    }

    @Test
    @DisplayName("有进展（输出正文/调用工具）后重新计时，长任务不能被误杀")
    void progressShouldResetTheWindow() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(clock::get);

        // 第一轮：思考很久但仍在时限内，随后调用了工具
        clock.addAndGet(java.time.Duration.ofMinutes(2).toNanos());
        guard.onThinking("想".repeat(10_000));
        guard.onProgress();

        // 第二轮：又思考很久
        clock.addAndGet(java.time.Duration.ofMinutes(2).toNanos());
        guard.onThinking("想".repeat(10_000));

        assertFalse(guard.isRunaway(), "每次有进展都应重新开始计时与计数，复杂任务不能被误判成跑飞");
        assertTrue(guard.describe().contains("10000"), "现状描述要能用于日志定位：" + guard.describe());
    }

    @Test
    @DisplayName("根本没收到思考内容时不算跑飞：那属于卡住，由读超时负责")
    void silenceIsNotRunaway() {
        AtomicLong clock = new AtomicLong();
        ThinkingRunawayGuard guard = new ThinkingRunawayGuard(clock::get);

        clock.addAndGet(java.time.Duration.ofHours(1).toNanos());
        assertFalse(guard.isRunaway(), "一个字都没收到时不应报'思考跑飞'");
    }
}
