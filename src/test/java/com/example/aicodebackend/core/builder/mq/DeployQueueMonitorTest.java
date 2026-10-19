package com.example.aicodebackend.core.builder.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署队列巡检的告警状态机
 * 守的是"只在状态切换时告警"这条规则：一条持续存在的积压只应该喊一次……
 */
class DeployQueueMonitorTest {

    private static final int WAITING_THRESHOLD = 24;

    private static final int DEAD_LETTER_THRESHOLD = 1;

    private DeployQueueMonitor newMonitor() {
        return new DeployQueueMonitor(WAITING_THRESHOLD, DEAD_LETTER_THRESHOLD);
    }

    @Test
    @DisplayName("等待队列达到阈值：只触发一次，持续超限不重复告警")
    void shouldFireWaitingAlertOnlyOnce() {
        DeployQueueMonitor monitor = newMonitor();

        List<DeployQueueMonitor.Alert> first = monitor.evaluate(new DeployQueueMonitor.Snapshot(30, 3, 0));
        assertEquals(List.of(DeployQueueMonitor.Alert.WAITING), first, "首次超阈值应触发 WAITING");
        assertTrue(monitor.firingAlerts().contains("WAITING"));

        List<DeployQueueMonitor.Alert> again = monitor.evaluate(new DeployQueueMonitor.Snapshot(40, 3, 0));
        assertTrue(again.isEmpty(), "持续超限不应重复触发，实际=" + again);
    }

    @Test
    @DisplayName("回落到阈值以下：触发一次恢复，之后不再重复")
    void shouldFireRecoveryOnce() {
        DeployQueueMonitor monitor = newMonitor();
        monitor.evaluate(new DeployQueueMonitor.Snapshot(30, 3, 0));

        List<DeployQueueMonitor.Alert> recovered = monitor.evaluate(new DeployQueueMonitor.Snapshot(0, 3, 0));
        assertEquals(List.of(DeployQueueMonitor.Alert.WAITING), recovered, "恢复也应报一次（日志里能看到【已恢复】）");
        assertTrue(monitor.firingAlerts().isEmpty(), "恢复后不应仍处于告警态");

        assertTrue(monitor.evaluate(new DeployQueueMonitor.Snapshot(0, 3, 0)).isEmpty(), "恢复正常后不应再报");
    }

    @Test
    @DisplayName("刚低于阈值不触发（边界：等于阈值才算积压）")
    void shouldUseThresholdAsBoundary() {
        DeployQueueMonitor monitor = newMonitor();

        assertTrue(monitor.evaluate(
                        new DeployQueueMonitor.Snapshot(WAITING_THRESHOLD - 1, 3, 0)).isEmpty(),
                "低于阈值不应告警");
        assertEquals(List.of(DeployQueueMonitor.Alert.WAITING),
                monitor.evaluate(new DeployQueueMonitor.Snapshot(WAITING_THRESHOLD, 3, 0)),
                "等于阈值应告警");
    }

    @Test
    @DisplayName("死信只要有 1 条就告警，且与等待队列互不影响")
    void shouldAlertDeadLetterIndependently() {
        DeployQueueMonitor monitor = newMonitor();

        List<DeployQueueMonitor.Alert> dead = monitor.evaluate(new DeployQueueMonitor.Snapshot(0, 3, 1));
        assertEquals(List.of(DeployQueueMonitor.Alert.DEAD_LETTER), dead);
        assertTrue(monitor.firingAlerts().contains("DEAD_LETTER"));
        assertTrue(!monitor.firingAlerts().contains("WAITING"), "等待队列正常时不应误报");

        // 两个通道同时告警：一次 evaluate 返回两条
        List<DeployQueueMonitor.Alert> both = monitor.evaluate(new DeployQueueMonitor.Snapshot(30, 3, 5));
        assertEquals(1, both.size(), "WAITING 是新触发，DEAD_LETTER 已在告警中不应重复：" + both);
        assertTrue(both.contains(DeployQueueMonitor.Alert.WAITING));
    }

    @Test
    @DisplayName("broker 查询失败（-1）不改变告警状态，避免误报\"已恢复\"")
    void shouldIgnoreUnavailableMetrics() {
        DeployQueueMonitor monitor = newMonitor();
        monitor.evaluate(new DeployQueueMonitor.Snapshot(30, 3, 0));

        List<DeployQueueMonitor.Alert> duringOutage =
                monitor.evaluate(new DeployQueueMonitor.Snapshot(-1, -1, -1));
        assertTrue(duringOutage.isEmpty(), "查询失败时不应报恢复，实际=" + duringOutage);
        assertTrue(monitor.firingAlerts().contains("WAITING"), "告警态应保持在（等 broker 恢复后再判断）");
    }
}
