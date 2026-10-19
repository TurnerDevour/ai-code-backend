package com.example.aicodebackend.model.vo;

import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 部署状态视图（异步部署的轮询返回体） */
@Data
public class DeployStatusVO implements Serializable {

    /** 应用 id */
    private Long appId;

    /** 部署状态：idle / deploying / ready / failed */
    private String status;

    /**
     * 本次提交是否被受理（仅 {@code POST /app/deploy/async} 的返回有意义）
     * true 表示后台任务已启动；false 表示当前状态不允许再次提交（例如已有部署在进行、或已经部署完成），
     * 此时返回体里带的是应用当前的真实状态，调用方应直接按 status/deployUrl 处理，而不是再次轮询等待变化。
     */
    private Boolean accepted;

    /** 面向用户的状态说明 */
    private String message;

    /** 部署地址，仅 status=ready 时返回 */
    private String deployUrl;

    /** 失败原因，仅 status=failed 时返回 */
    private String errorMessage;

    /** 最近一次部署完成时间（可能为空） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deployedTime;

    /** 排队位置（从 1 开始），仅 status=queued 时有值；用于前端展示"前面还有几个" */
    private Integer queuePosition;

    /** 当前等待构建的任务总数，仅 status=queued 时有值 */
    private Integer queueSize;

    /** 构建并发数（同时最多几个任务在构建），便于前端解释"为什么要排队" */
    private Integer workerCount;

    /**
     * 已部署的产物是否已经落后于当前代码（即"代码改过、需要重新部署"）
     * 判定依据：应用的编辑时间晚于最近一次部署完成时间。
     * 前端据此把"已部署"显示成"代码已更新，可重新部署"，而不是让用户一直看到旧站点。
     */
    private Boolean deployStale;

    @Serial
    private static final long serialVersionUID = 1L;

    /** 构建"进行中/失败"的返回体 */
    public static DeployStatusVO of(Long appId, DeployStatusEnum status, String message, String errorMessage) {
        DeployStatusVO vo = new DeployStatusVO();
        vo.setAppId(appId);
        vo.setStatus(status.getValue());
        vo.setMessage(message);
        vo.setErrorMessage(errorMessage);
        return vo;
    }

    /** 构建"提交被受理"的返回体 */
    public static DeployStatusVO accepted(Long appId, DeployStatusEnum status, String message, Integer queuePosition) {
        DeployStatusVO vo = of(appId, status, message, null);
        vo.setAccepted(true);
        vo.setQueuePosition(queuePosition);
        return vo;
    }

    /** 构建"排队中"的返回体 */
    public static DeployStatusVO queued(Long appId, Integer queuePosition, Integer queueSize, Integer workerCount) {
        // queueSize 为 -1 表示 broker 水位查询失败（见 DeployQueueManager#METRICS_UNAVAILABLE）：
        // 此时只报位次，不报总数，避免把"查不到"显示成"共 -1 个等待"
        String message;
        if (queuePosition == null) {
            message = "已进入部署队列，等待构建中";
        } else if (queueSize == null || queueSize < 0) {
            message = "排队中（当前第 " + queuePosition + " 位，同时最多构建 " + workerCount + " 个）";
        } else {
            message = "排队中（当前第 " + queuePosition + " 位，共 " + queueSize + " 个等待，同时最多构建 "
                    + workerCount + " 个）";
        }
        DeployStatusVO vo = of(appId, DeployStatusEnum.QUEUED, message, null);
        vo.setQueuePosition(queuePosition);
        vo.setQueueSize(queueSize);
        vo.setWorkerCount(workerCount);
        return vo;
    }

    /** 构建"提交未被受理"的返回体（带上应用当前真实状态） */
    public static DeployStatusVO rejected(Long appId, DeployStatusEnum status, String message, String deployUrl, String errorMessage) {
        DeployStatusVO vo = of(appId, status == null ? DeployStatusEnum.IDLE : status, message, errorMessage);
        vo.setDeployUrl(deployUrl);
        vo.setAccepted(false);
        return vo;
    }

    /** 构建"部署完成"的返回体 */
    public static DeployStatusVO ready(Long appId, String deployUrl, LocalDateTime deployedTime) {
        DeployStatusVO vo = of(appId, DeployStatusEnum.READY, "部署完成，可直接访问", null);
        vo.setDeployUrl(deployUrl);
        vo.setDeployedTime(deployedTime);
        return vo;
    }
}
