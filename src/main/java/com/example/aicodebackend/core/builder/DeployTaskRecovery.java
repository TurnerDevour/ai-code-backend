package com.example.aicodebackend.core.builder;

import cn.hutool.core.util.StrUtil;
import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.mybatisflex.core.constant.SqlOperator;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 部署任务恢复扫描
 * 解决两类"没有消息/消息不可靠"的卡死：同步请求进程崩溃、数据库抖动导致状态未回滚。
 * 构建期间有 {@link DeployHeartbeat} 续租，心跳超过阈值未更新即可判定进程已死并落 failed。
 */
@Slf4j
@Component
public class DeployTaskRecovery implements ApplicationRunner {

    private final AppMapper appMapper;

    private final DeployQueueManager deployQueueManager;

    private final boolean enabled;

    private final long intervalMillis;

    private final long deployingTimeoutSeconds;

    private final long queuedTimeoutSeconds;

    private final int batchSize;

    private ScheduledExecutorService scheduler;

    public DeployTaskRecovery(AppMapper appMapper,
                              DeployQueueManager deployQueueManager,
                              @Value("${deploy.recovery.enabled:true}") boolean enabled,
                              @Value("${deploy.recovery.interval-seconds:60}") long intervalSeconds,
                              @Value("${deploy.recovery.deploying-timeout-seconds:300}") long deployingTimeoutSeconds,
                              @Value("${deploy.recovery.queued-timeout-seconds:900}") long queuedTimeoutSeconds,
                              @Value("${deploy.recovery.batch-size:50}") int batchSize) {
        this.appMapper = appMapper;
        this.deployQueueManager = deployQueueManager;
        this.enabled = enabled;
        this.intervalMillis = TimeUnit.SECONDS.toMillis(Math.max(10L, intervalSeconds));
        this.deployingTimeoutSeconds = Math.max(30L, deployingTimeoutSeconds);
        this.queuedTimeoutSeconds = Math.max(60L, queuedTimeoutSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("部署任务恢复扫描已关闭（deploy.recovery.enabled=false）");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deploy-recovery");
            thread.setDaemon(true);
            return thread;
        });
        // 首次延迟一个周期：避免应用启动瞬间和数据库结构自愈/队列声明抢跑
        scheduler.scheduleWithFixedDelay(this::tickQuietly, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("部署任务恢复扫描已启动：间隔={}s, deploying 超时={}s, queued 超时={}s, 批量={}",
                TimeUnit.MILLISECONDS.toSeconds(intervalMillis), deployingTimeoutSeconds, queuedTimeoutSeconds, batchSize);
    }

    /** 扫描一次：deploying 超时落 failed，queued 超时重投 */
    void tick() {
        recoverDeploying();
        recoverQueued();
    }

    /** 扫描心跳过期的 deploying，落 failed */
    void recoverDeploying() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(deployingTimeoutSeconds);
        for (App app : findStale(DeployStatusEnum.DEPLOYING.getValue(), cutoff)) {
            if (markStaleDeploying(app, cutoff)) {
                log.warn("恢复扫描：deploying 心跳过期，已落 failed，appId: {}, taskId: {}, heartbeat: {}",
                        app.getId(), app.getDeployTaskId(), app.getDeployHeartbeat());
            }
        }
    }

    /** 扫描过期的 queued：按原 taskId 重新入队（旧任务 taskId 为 null 也可以重投） */
    void recoverQueued() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(queuedTimeoutSeconds);
        for (App app : findStale(DeployStatusEnum.QUEUED.getValue(), cutoff)) {
            if (app.getId() == null) {
                continue;
            }
            String taskId = StrUtil.isBlank(app.getDeployTaskId()) ? null : app.getDeployTaskId();
            try {
                // 同一 taskId 重新发布：若原消息其实还在队列里，消费端会凭 taskId 只构建一次。
                // 升级前的旧任务 taskId 为 null，同样可以重投：首次 claim 只认 queued，重复消息会被跳过。
                deployQueueManager.submit(app.getId(), app.getDeployOperatorId(), taskId);
                touchQueuedHeartbeat(app, cutoff);
                log.warn("恢复扫描：queued 超时，已按原 taskId 重新入队，appId: {}, taskId: {}",
                        app.getId(), taskId);
            } catch (RuntimeException e) {
                // broker 不可用/队列满：下一个周期再试，不能把状态改成 failed（消息可能仍在队列里）
                log.warn("恢复扫描：queued 任务重新入队失败，下个周期重试，appId: {}, taskId: {}, reason: {}",
                        app.getId(), taskId, e.getMessage());
            }
        }
    }

    /**
     * 查询状态匹配、且心跳（或旧数据的 update_time）早于 cutoff 的应用
     * 拆成两条查询而不是 OR：MyBatis-Flex 的 OR/IS NULL 组合在项目里踩过坑，
     * 两条简单条件更可靠，也便于走 deploy_status 索引。
     */
    private List<App> findStale(String status, LocalDateTime cutoff) {
        Map<Long, App> result = new LinkedHashMap<>();
        QueryWrapper heartbeatExpired = QueryWrapper.create()
                .eq("deploy_status", status)
                .lt("deploy_heartbeat", cutoff)
                .limit(batchSize);
        for (App app : Objects.requireNonNull(appMapper.selectListByQuery(heartbeatExpired))) {
            result.put(app.getId(), app);
        }
        QueryWrapper noHeartbeat = QueryWrapper.create()
                .eq("deploy_status", status)
                .isNull("deploy_heartbeat")
                .lt("update_time", cutoff)
                .limit(batchSize);
        for (App app : Objects.requireNonNull(appMapper.selectListByQuery(noHeartbeat))) {
            result.putIfAbsent(app.getId(), app);
        }
        return new ArrayList<>(result.values());
    }

    /** 把心跳过期的 deploying 条件更新为 failed */
    private boolean markStaleDeploying(App app, LocalDateTime cutoff) {
        App failed = new App();
        failed.setDeployStatus(DeployStatusEnum.FAILED.getValue());
        failed.setDeployError("部署任务执行超时或进程中断，请重新提交");
        failed.setDeployTaskId("");
        QueryCondition condition = baseCondition(app, DeployStatusEnum.DEPLOYING, cutoff);
        int affected = appMapper.updateByCondition(failed, condition);
        return affected == 1;
    }

    /** 重投成功后刷新 queued 的心跳，避免下一轮立即又重投 */
    private void touchQueuedHeartbeat(App app, LocalDateTime cutoff) {
        App touch = new App();
        touch.setDeployHeartbeat(LocalDateTime.now());
        QueryCondition condition = baseCondition(app, DeployStatusEnum.QUEUED, cutoff);
        appMapper.updateByCondition(touch, condition);
    }

    /** 构造"仍是这个任务、且心跳确实过期"的条件，防止扫描覆盖用户刚重新提交的新任务 */
    private QueryCondition baseCondition(App app, DeployStatusEnum status, LocalDateTime cutoff) {
        QueryCondition condition = QueryCondition.create(new QueryColumn("id"), app.getId())
                .and(QueryCondition.create(new QueryColumn("deploy_status"), status.getValue()));
        if (app.getDeployHeartbeat() == null) {
            condition.and(new QueryColumn("deploy_heartbeat").isNull(true))
                    .and(QueryCondition.create(new QueryColumn("update_time"), SqlOperator.LT, cutoff));
        } else {
            condition.and(QueryCondition.create(new QueryColumn("deploy_heartbeat"), SqlOperator.LT, cutoff));
        }
        if (app.getDeployTaskId() == null) {
            condition.and(new QueryColumn("deploy_task_id").isNull(true));
        } else {
            // 空串是"已清空 token"的显式值，必须用 = '' 匹配，不能当成 NULL
            condition.and(QueryCondition.create(new QueryColumn("deploy_task_id"), app.getDeployTaskId()));
        }
        return condition;
    }

    /** 扫描异常不能打死定时线程 */
    private void tickQuietly() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("部署任务恢复扫描失败（下次继续）：{}", e.getMessage());
        }
    }

    /** 停止扫描 */
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
