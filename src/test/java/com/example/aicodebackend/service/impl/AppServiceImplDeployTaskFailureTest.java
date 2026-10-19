package com.example.aicodebackend.service.impl;

import com.example.aicodebackend.core.builder.DeployQueueManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 部署任务失败语义的回归
 * 最关键的一条：基础设施异常必须抛回给 {@code DeployTaskConsumer}，由它重投/进死信……
 */
class AppServiceImplDeployTaskFailureTest {

    @Test
    @DisplayName("基础设施异常（数据库不可用）必须抛给消费者重投，不能吞掉 ACK")
    void infrastructureFailureShouldPropagate() {
        AppServiceImpl appService = spy(new AppServiceImpl());
        doThrow(new IllegalStateException("数据库连接失败")).when(appService).getById(1L);

        DeployQueueManager.DeployTask task = new DeployQueueManager.DeployTask(1L, 1L, "task-1", 0, false);

        assertThrows(IllegalStateException.class, () -> appService.handleDeployTask(task),
                "基础设施异常被吞掉会导致消息被 ACK、应用卡在 queued/deploying");
    }

    @Test
    @DisplayName("应用已被删除：直接丢弃消息，不触发重试")
    void missingAppShouldBeDropped() {
        AppServiceImpl appService = spy(new AppServiceImpl());
        doReturn(null).when(appService).getById(2L);

        DeployQueueManager.DeployTask task = new DeployQueueManager.DeployTask(2L, 1L, "task-2", 0, false);

        assertDoesNotThrow(() -> appService.handleDeployTask(task));
    }
}
