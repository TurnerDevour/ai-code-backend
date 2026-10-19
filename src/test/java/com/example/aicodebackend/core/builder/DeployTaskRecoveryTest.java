package com.example.aicodebackend.core.builder;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.mybatisflex.core.query.QueryCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 恢复扫描：心跳过期落 failed、queued 超时按 taskId 重投 */
class DeployTaskRecoveryTest {

    private final AppMapper appMapper = mock(AppMapper.class);

    private final DeployQueueManager deployQueueManager = mock(DeployQueueManager.class);

    private final DeployTaskRecovery recovery = new DeployTaskRecovery(
            appMapper, deployQueueManager, true, 60, 300, 900, 50);

    @Test
    @DisplayName("deploying 心跳过期：条件更新为 failed，并清空 taskId")
    void staleDeployingShouldBeMarkedFailed() {
        App app = new App();
        app.setId(1L);
        app.setDeployStatus(DeployStatusEnum.DEPLOYING.getValue());
        app.setDeployTaskId("task-a");
        app.setDeployHeartbeat(LocalDateTime.now().minusMinutes(10));
        when(appMapper.selectListByQuery(any())).thenReturn(List.of(app));
        when(appMapper.updateByCondition(any(App.class), any(QueryCondition.class))).thenReturn(1);

        recovery.recoverDeploying();

        ArgumentCaptor<App> entity = ArgumentCaptor.forClass(App.class);
        verify(appMapper).updateByCondition(entity.capture(), any(QueryCondition.class));
        assertEquals(DeployStatusEnum.FAILED.getValue(), entity.getValue().getDeployStatus());
        assertEquals("", entity.getValue().getDeployTaskId(), "恢复后必须清 token，避免迟到消息重跑");
        assertTrue(entity.getValue().getDeployError().contains("超时"));
    }

    @Test
    @DisplayName("queued 超时且有 taskId：按原 taskId 重新入队，并刷新心跳")
    void staleQueuedShouldRepublishWithSameTaskId() {
        App app = new App();
        app.setId(2L);
        app.setDeployStatus(DeployStatusEnum.QUEUED.getValue());
        app.setDeployTaskId("task-b");
        app.setDeployOperatorId(7L);
        app.setDeployHeartbeat(LocalDateTime.now().minusMinutes(30));
        when(appMapper.selectListByQuery(any())).thenReturn(List.of(app));
        when(appMapper.updateByCondition(any(App.class), any(QueryCondition.class))).thenReturn(1);

        recovery.recoverQueued();

        verify(deployQueueManager).submit(2L, 7L, "task-b");
        ArgumentCaptor<App> entity = ArgumentCaptor.forClass(App.class);
        verify(appMapper).updateByCondition(entity.capture(), any(QueryCondition.class));
        assertTrue(entity.getValue().getDeployHeartbeat() != null, "重投后必须刷新心跳，避免下一轮立即重复");
    }

    @Test
    @DisplayName("queued 超时且没有 taskId：旧任务也按 null 重投（重复消息由 claim 规则挡住）")
    void staleQueuedWithoutTaskIdShouldRepublish() {
        App app = new App();
        app.setId(3L);
        app.setDeployStatus(DeployStatusEnum.QUEUED.getValue());
        app.setDeployHeartbeat(LocalDateTime.now().minusMinutes(30));
        when(appMapper.selectListByQuery(any())).thenReturn(List.of(app));
        when(appMapper.updateByCondition(any(App.class), any(QueryCondition.class))).thenReturn(1);

        recovery.recoverQueued();

        verify(deployQueueManager).submit(3L, null, null);
        ArgumentCaptor<App> entity = ArgumentCaptor.forClass(App.class);
        verify(appMapper).updateByCondition(entity.capture(), any(QueryCondition.class));
        assertTrue(entity.getValue().getDeployHeartbeat() != null, "重投后必须刷新心跳");
    }
}
