package com.example.aicodebackend.controller;

import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.core.builder.mq.DeployQueueMonitor;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    @Resource
    private DeployQueueManager deployQueueManager;

    @Resource
    private DeployQueueMonitor deployQueueMonitor;

    @GetMapping
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }

    /**
     * 部署队列水位（运维观测用）
     * 数据来自 RabbitMQ（{@code RabbitAdmin#getQueueInfo}），因此是全局口径：
     * {@code waiting} 包含所有实例共享的排队任务，{@code consumers} 是当前订阅该队列的消费者总数……
     */
    @GetMapping("/deploy-queue")
    public BaseResponse<Map<String, Object>> deployQueueStatus() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("broker", "rabbitmq");
        metrics.put("workers", deployQueueManager.getWorkerCount());
        metrics.put("maxQueueSize", deployQueueManager.getMaxQueueSize());
        metrics.put("waiting", deployQueueManager.queueSize());
        metrics.put("executing", deployQueueManager.executingCount());
        metrics.put("inFlight", deployQueueManager.inFlightCount());
        metrics.put("consumers", deployQueueManager.consumerCount());
        metrics.put("deadLetters", deployQueueManager.deadLetterCount());
        metrics.put("syncPermits", deployQueueManager.getSyncPermitCount());
        metrics.put("syncWaiting", deployQueueManager.syncWaitingCount());
        metrics.put("alerts", deployQueueMonitor.firingAlerts());
        return ResultUtils.success(metrics);
    }
}
