package com.example.aicodebackend.service.impl;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.mybatisflex.core.dialect.impl.CommonsDialectImpl;
import com.mybatisflex.core.query.QueryCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署状态抢占的条件更新语义
 * 这些边界如果不成立，重投会空转、旧消息会覆盖新任务……
 */
class AppServiceImplDeployClaimTest {

    /** 捕获最后一次条件更新的实体与条件 */
    private static final class CapturingMapper {

        private App entity;

        private QueryCondition condition;

        int updateByCondition(App entity, QueryCondition condition) {
            this.entity = entity;
            this.condition = condition;
            return 1;
        }

        String whereSql() {
            return condition.toSql(null, new CommonsDialectImpl());
        }
    }

    private static AppServiceImpl newAppService(CapturingMapper table) {
        AppServiceImpl appService = new AppServiceImpl();
        AppMapper mapper = (AppMapper) Proxy.newProxyInstance(
                AppMapper.class.getClassLoader(),
                new Class<?>[]{AppMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "updateByCondition" -> table.updateByCondition((App) args[0], (QueryCondition) args[1]);
                    case "toString" -> "CapturingAppMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        ReflectionTestUtils.setField(appService, "mapper", mapper);
        return appService;
    }

    @Test
    @DisplayName("首次消费：条件带 taskId，只允许 queued/failed，不能从 deploying 重复构建")
    void firstAttemptShouldRequireTaskIdAndNotClaimDeploying() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean claimed = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "claimDeploying", 1L, "task-a", 0, false);

        assertEquals(Boolean.TRUE, claimed);
        String sql = table.whereSql();
        assertTrue(sql.contains("`deploy_task_id` = ?"), "幂等抢占必须匹配 taskId，实际 WHERE=" + sql);
        assertTrue(sql.contains("IN"), "应允许 queued/failed 两种可执行状态，实际 WHERE=" + sql);
        assertEquals("deploying", table.entity.getDeployStatus());
    }

    @Test
    @DisplayName("重试消息：taskId 匹配且 attempt>0 时允许从 deploying 恢复")
    void retryShouldClaimQueuedOrDeploying() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean claimed = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "claimDeploying", 1L, "task-a", 2, false);

        assertEquals(Boolean.TRUE, claimed);
        String sql = table.whereSql();
        assertTrue(sql.contains("`deploy_task_id` = ?"), "重投也必须匹配 taskId，实际 WHERE=" + sql);
        assertTrue(sql.contains("IN"), "重投应允许 queued/failed/deploying，实际 WHERE=" + sql);
    }

    @Test
    @DisplayName("进程崩溃重投：redelivered=true 时同一任务可以从 deploying 继续")
    void redeliveredShouldClaimDeploying() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean claimed = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "claimDeploying", 1L, "task-a", 0, true);

        assertEquals(Boolean.TRUE, claimed);
        assertTrue(table.whereSql().contains("IN"), "崩溃重投应允许 deploying 恢复");
    }

    @Test
    @DisplayName("同步部署从读到的旧状态 CAS，并写入 taskId 与发起人")
    void syncClaimShouldCasFromObservedStatus() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean claimed = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "claimSyncDeploying", 1L, "failed", 9L, "sync-task");

        assertEquals(Boolean.TRUE, claimed);
        String sql = table.whereSql();
        assertTrue(sql.contains("`deploy_status` = ?"), "同步抢占必须带上旧状态条件，实际 WHERE=" + sql);
        assertEquals("deploying", table.entity.getDeployStatus());
        assertEquals(9L, table.entity.getDeployOperatorId(), "同步部署也要记录发起人，便于状态查询授权");
        assertEquals("sync-task", table.entity.getDeployTaskId(), "同步部署也要写 taskId，旧 MQ 消息才不会误抢");
    }

    @Test
    @DisplayName("历史数据状态为 NULL：用 IS NULL 做条件更新，不做无条件覆盖")
    void nullStatusShouldStillUseCas() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean claimed = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "claimSyncDeploying", 1L, null, 9L, "sync-task");

        assertEquals(Boolean.TRUE, claimed);
        String sql = table.whereSql();
        assertTrue(sql.contains("IS NULL"), "旧状态为空时必须用 IS NULL 做 CAS，实际 WHERE=" + sql);
    }

    @Test
    @DisplayName("发布确认超时回滚：只回滚仍属于本任务的 queued，并保留 taskId")
    void rollbackShouldOnlyTouchMatchingQueuedTask() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        Boolean rolledBack = (Boolean) ReflectionTestUtils.invokeMethod(
                appService, "rollbackQueuedToFailed", 1L, "task-a", "确认超时");

        assertEquals(Boolean.TRUE, rolledBack);
        String sql = table.whereSql();
        assertTrue(sql.contains("`deploy_status` = ?"), "回滚必须只命中 queued，实际 WHERE=" + sql);
        assertTrue(sql.contains("`deploy_task_id` = ?"), "回滚必须匹配 taskId，实际 WHERE=" + sql);
        assertEquals("failed", table.entity.getDeployStatus());
        assertEquals("确认超时", table.entity.getDeployError());
        // 故意不设置 deployTaskId：保留数据库里的 token，消息真到了 worker 仍能恢复
        assertNull(table.entity.getDeployTaskId());
    }

    @Test
    @DisplayName("死信落库：只把仍属于该 taskId 的 queued/deploying 改成 failed，并清空 token")
    void deadLetterShouldFailOnlyMatchingTask() {
        CapturingMapper table = new CapturingMapper();
        AppServiceImpl appService = newAppService(table);

        appService.handleDeadLetter(
                new com.example.aicodebackend.core.builder.DeployQueueManager.DeployTask(
                        1L, 1L, "task-a", 0, false));

        String sql = table.whereSql();
        assertTrue(sql.contains("`deploy_task_id` = ?"), "死信落库必须匹配 taskId，实际 WHERE=" + sql);
        assertTrue(sql.contains("IN"), "死信落库应覆盖 queued/deploying，实际 WHERE=" + sql);
        assertEquals("failed", table.entity.getDeployStatus());
        assertEquals("", table.entity.getDeployTaskId(), "死信后必须清空 token，防止迟到消息重跑");
    }
}
