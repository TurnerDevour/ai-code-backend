package com.example.aicodebackend.core.builder.mq;

import com.example.aicodebackend.core.builder.DeployQueueManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 部署队列的健康巡检与告警
 * 为什么需要它：{@code /api/health/deploy-queue} 只是"能查"，没人会一直盯着它。
 * 队列积压和死信堆积都是会自己变坏的状态（构建慢了 → 排队变长 → 用户全部被拒……
 */
@Slf4j
@Component
public class DeployQueueMonitor implements ApplicationRunner {

    /** 告警通道：一条告警从"正常"变成"触发"，或者从"触发"恢复，都按通道记录 */
    public enum Alert {
        /** 等待队列深度超过阈值：说明构建速度跟不上提交速度 */
        WAITING,
        /** 死信队列有积压：说明有任务重试耗尽仍失败，需要人工排查 */
        DEAD_LETTER
    }

    /** 一次巡检读到的水位快照 */
    public record Snapshot(int waiting, int consumers, int deadLetters) {
    }

    private final DeployQueueManager deployQueueManager;

    private final boolean enabled;

    private final long intervalMillis;

    private final int waitingThreshold;

    private final int deadLetterThreshold;

    /** 当前正在触发的告警（用于"只在状态切换时打日志"） */
    private final Set<Alert> firing = EnumSet.noneOf(Alert.class);

    /** 巡检线程 */
    private ScheduledExecutorService scheduler;

    /**
     * 容器使用的构造器
     * 必须显式标注 {@link Autowired}：类里还有供单测使用的构造器，
     * 两个构造器并存时 Spring 不会自己挑（与 {@code ScreenshotTempCleaner} 同一个坑）。
     */
    @Autowired
    public DeployQueueMonitor(DeployQueueManager deployQueueManager,
                              @Value("${deploy.queue.monitor.enabled:true}") boolean enabled,
                              @Value("${deploy.queue.monitor.interval-seconds:60}") long intervalSeconds,
                              @Value("${deploy.queue.monitor.waiting-threshold:24}") int waitingThreshold,
                              @Value("${deploy.queue.monitor.dead-letter-threshold:1}") int deadLetterThreshold) {
        this.deployQueueManager = deployQueueManager;
        this.enabled = enabled;
        this.intervalMillis = TimeUnit.SECONDS.toMillis(Math.max(5, intervalSeconds));
        this.waitingThreshold = Math.max(1, waitingThreshold);
        this.deadLetterThreshold = Math.max(1, deadLetterThreshold);
    }

    /** 供单测使用：不启动线程，只暴露 {@link #evaluate(Snapshot)} */
    DeployQueueMonitor(int waitingThreshold, int deadLetterThreshold) {
        this(null, true, 60, waitingThreshold, deadLetterThreshold);
    }

    /** 启动巡检 */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("部署队列巡检已关闭（deploy.queue.monitor.enabled=false）");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deploy-queue-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tickQuietly, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("部署队列巡检已启动：间隔={}s, 等待告警阈值={}, 死信告警阈值={}",
                TimeUnit.MILLISECONDS.toSeconds(intervalMillis), waitingThreshold, deadLetterThreshold);
    }

    /** 当前正在触发的告警（给健康接口用） */
    public Set<String> firingAlerts() {
        synchronized (firing) {
            return firing.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
        }
    }

    /** 巡检一次的包装：任何异常都不能把定时线程打死 */
    void tickQuietly() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("部署队列巡检失败（下次继续）：{}", e.getMessage());
        }
    }

    /** 巡检一次：读水位 → 比对阈值 → 只在状态切换时打日志 */
    void tick() {
        Snapshot snapshot = new Snapshot(
                deployQueueManager.queueSize(),
                deployQueueManager.consumerCount(),
                deployQueueManager.deadLetterCount());
        List<Alert> changed = evaluate(snapshot);
        for (Alert alert : changed) {
            if (firing.contains(alert)) {
                if (alert == Alert.DEAD_LETTER) {
                    log.error("【部署队列告警】死信队列积压：当前 {} 条（阈值 {}）。"
                                    + "说明有部署任务重试耗尽仍失败，请按 DOCKER.md 7.1 节排查 deploy.dlq",
                            snapshot.deadLetters(), deadLetterThreshold);
                } else {
                    log.warn("【部署队列告警】等待队列积压：当前 {} 个（阈值 {}，消费者 {} 个）。"
                                    + "构建速度可能跟不上提交速度，可考虑调大 workers 或降低提交频率",
                            snapshot.waiting(), waitingThreshold, snapshot.consumers());
                }
            } else {
                log.info("【部署队列恢复】{} 已恢复正常：等待 {}、死信 {}、消费者 {}",
                        alert, snapshot.waiting(), snapshot.deadLetters(), snapshot.consumers());
            }
        }
    }

    /**
     * 比对阈值并更新告警状态
     * 纯内存计算，便于单测直接验证"什么时候触发、什么时候恢复"。
     * 查询失败（-1）不参与判断：broker 短暂不可用时不应该误报"队列已恢复正常"。
     */
    List<Alert> evaluate(Snapshot snapshot) {
        List<Alert> changed = new ArrayList<>(2);
        synchronized (firing) {
            // 指标为 -1 表示这次查不到：跳过该通道、保持原状态。
            // 不能把它当成"已恢复"——broker 抖一下就把告警清掉，等于没告警。
            if (snapshot.waiting() >= 0) {
                changed.addAll(update(Alert.WAITING, snapshot.waiting() >= waitingThreshold));
            }
            if (snapshot.deadLetters() >= 0) {
                changed.addAll(update(Alert.DEAD_LETTER, snapshot.deadLetters() >= deadLetterThreshold));
            }
        }
        return changed;
    }

    /** 按"是否应该触发"更新单个通道的状态 */
    private List<Alert> update(Alert alert, boolean shouldFire) {
        boolean currentlyFiring = firing.contains(alert);
        if (shouldFire == currentlyFiring) {
            return List.of();
        }
        if (shouldFire) {
            firing.add(alert);
        } else {
            firing.remove(alert);
        }
        return List.of(alert);
    }
}
