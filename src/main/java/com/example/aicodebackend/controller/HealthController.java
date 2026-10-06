package com.example.aicodebackend.controller;

import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.ResultUtils;
import com.example.aicodebackend.core.builder.DeployQueueManager;
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

    @GetMapping
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }

    /**
     * 部署队列水位（运维观测用）
     * <p>
     * 返回当前等待构建的任务数、正在构建的任务数、构建并发上限与队列容量，
     * 便于确认"限流是否生效"以及压测/线上是否需要调整 workers。
     *
     * @return 队列指标
     */
    @GetMapping("/deploy-queue")
    public BaseResponse<Map<String, Object>> deployQueueStatus() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("workers", deployQueueManager.getWorkerCount());
        metrics.put("maxQueueSize", deployQueueManager.getMaxQueueSize());
        metrics.put("waiting", deployQueueManager.queueSize());
        metrics.put("executing", deployQueueManager.executingCount());
        metrics.put("inFlight", deployQueueManager.inFlightCount());
        return ResultUtils.success(metrics);
    }
}
