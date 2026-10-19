package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 部署任务心跳
 * 构建（同步或 MQ worker）期间每 {@code deploy.recovery.heartbeat-interval-seconds} 秒刷新一次
 * {@code app.deploy_heartbeat}。恢复扫描用"心跳是否新鲜"区分"构建还在跑"和"进程已经崩了"……
 */
@Slf4j
@Component
public class DeployHeartbeat {

    private final AppMapper appMapper;

    private final boolean enabled;

    private final long intervalSeconds;

    private final ScheduledExecutorService scheduler;

    public DeployHeartbeat(AppMapper appMapper,
                           @Value("${deploy.recovery.enabled:true}") boolean enabled,
                           @Value("${deploy.recovery.heartbeat-interval-seconds:30}") long intervalSeconds) {
        this.appMapper = appMapper;
        this.enabled = enabled;
        this.intervalSeconds = Math.max(5L, intervalSeconds);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deploy-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 开始为一次构建续租 */
    public Handle start(Long appId, String taskId) {
        if (!enabled || appId == null) {
            return Handle.NOOP;
        }
        // 注意：升级前的旧任务 taskId 为空，仍然可以续租（只按 appId + deploying 条件），
        // 否则旧任务构建超过扫描阈值会被误判成崩溃。
        try {
            // 心跳绑定构建线程：如果构建线程死了但 finally 没来得及执行（Error/ThreadDeath），
            // 调度器也应停止续租，让恢复扫描把 deploying 收敛掉。
            Thread owner = Thread.currentThread();
            java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> futureRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                ScheduledFuture<?> current = futureRef.get();
                if (!owner.isAlive()) {
                    if (current != null) {
                        current.cancel(false);
                    }
                    return;
                }
                beatSafely(appId, taskId);
            }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            futureRef.set(future);
            return () -> future.cancel(false);
        } catch (RuntimeException e) {
            // 调度器已关闭（应用停机）：不阻塞构建收尾
            log.debug("部署心跳调度失败（应用可能正在关闭），appId: {}, taskId: {}", appId, taskId, e);
            return Handle.NOOP;
        }
    }

    /** 刷新一次心跳（条件更新：只刷新仍属于该任务且仍在 deploying 的行） */
    void beat(Long appId, String taskId) {
        App heartbeat = new App();
        heartbeat.setDeployHeartbeat(LocalDateTime.now());
        QueryCondition condition = QueryCondition.create(new QueryColumn("id"), appId)
                .and(QueryCondition.create(new QueryColumn("deploy_status"),
                        DeployStatusEnum.DEPLOYING.getValue()));
        if (taskId != null && !taskId.isBlank()) {
            condition.and(QueryCondition.create(new QueryColumn("deploy_task_id"), taskId));
        }
        int affected = appMapper.updateByCondition(heartbeat, condition);
        if (affected == 0) {
            log.debug("部署心跳未命中（任务已结束或已被接管），appId: {}, taskId: {}", appId, taskId);
        }
    }

    /** 心跳失败不能影响构建：数据库抖动时下一个周期再试 */
    private void beatSafely(Long appId, String taskId) {
        try {
            beat(appId, taskId);
        } catch (Exception e) {
            log.debug("部署心跳刷新失败（下一个周期重试），appId: {}, taskId: {}", appId, taskId, e);
        }
    }

    /** 停止所有心跳 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 心跳租约 */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {

        /** 停止续租 */
        @Override
        void close();

        /** 不需要续租时使用 */
        Handle NOOP = () -> {
            // no-op
        };
    }
}
