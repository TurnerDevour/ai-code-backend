package com.example.aicodebackend.controller;

import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.core.builder.mq.DeployQueueMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康接口的部署队列指标回归
 * 同步部署的背压以前完全不可见：额度被占满、请求在排队，运维只能从日志猜……
 */
class HealthControllerTest {

    @Test
    @DisplayName("部署队列指标包含同步额度与同步排队数")
    void shouldExposeSyncDeployMetrics() {
        DeployQueueManager manager = mock(DeployQueueManager.class);
        when(manager.getWorkerCount()).thenReturn(3);
        when(manager.getMaxQueueSize()).thenReturn(32);
        when(manager.queueSize()).thenReturn(0);
        when(manager.executingCount()).thenReturn(1);
        when(manager.inFlightCount()).thenReturn(1);
        when(manager.consumerCount()).thenReturn(3);
        when(manager.deadLetterCount()).thenReturn(0);
        when(manager.getSyncPermitCount()).thenReturn(1);
        when(manager.syncWaitingCount()).thenReturn(2);

        DeployQueueMonitor monitor = mock(DeployQueueMonitor.class);
        when(monitor.firingAlerts()).thenReturn(Set.of());

        HealthController controller = new HealthController();
        ReflectionTestUtils.setField(controller, "deployQueueManager", manager);
        ReflectionTestUtils.setField(controller, "deployQueueMonitor", monitor);

        BaseResponse<Map<String, Object>> response = controller.deployQueueStatus();

        assertEquals(0, response.getCode());
        assertEquals(1, response.getData().get("syncPermits"), "应暴露每实例同步构建额度");
        assertEquals(2, response.getData().get("syncWaiting"), "应暴露正在排队等额度的请求数");
        assertEquals(3, response.getData().get("workers"), "原有指标不能丢");
    }
}
