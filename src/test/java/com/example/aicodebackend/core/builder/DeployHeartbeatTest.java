package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.mybatisflex.core.dialect.impl.CommonsDialectImpl;
import com.mybatisflex.core.query.QueryCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 部署心跳：只刷新 deploy_heartbeat，且必须按 taskId/status 条件更新 */
class DeployHeartbeatTest {

    private final AppMapper appMapper = mock(AppMapper.class);

    private final DeployHeartbeat heartbeat = new DeployHeartbeat(appMapper, true, 30);

    @AfterEach
    void tearDown() {
        heartbeat.shutdown();
    }

    @Test
    @DisplayName("刷新心跳：写 heartbeat，并带上 taskId 与 deploying 条件")
    void beatShouldUpdateHeartbeatConditionally() {
        when(appMapper.updateByCondition(any(App.class), any(QueryCondition.class))).thenReturn(1);

        heartbeat.beat(1L, "task-a");

        ArgumentCaptor<App> entity = ArgumentCaptor.forClass(App.class);
        ArgumentCaptor<QueryCondition> condition = ArgumentCaptor.forClass(QueryCondition.class);
        verify(appMapper).updateByCondition(entity.capture(), condition.capture());
        assertNotNull(entity.getValue().getDeployHeartbeat(), "必须刷新心跳时间");
        String sql = condition.getValue().toSql(null, new CommonsDialectImpl());
        assertTrue(sql.contains("deploy_task_id"), "心跳必须按 taskId 条件更新，实际 WHERE=" + sql);
        assertTrue(sql.contains("deploy_status"), "心跳只能刷新 deploying 行，实际 WHERE=" + sql);
    }

    @Test
    @DisplayName("没有 taskId 的旧任务也能按 appId 续租，避免构建超过扫描阈值被误判")
    void legacyTaskShouldStillRenew() {
        DeployHeartbeat.Handle handle = heartbeat.start(1L, null);
        assertNotNull(handle);
        handle.close();
    }
}
