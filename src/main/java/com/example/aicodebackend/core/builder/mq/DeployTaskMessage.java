package com.example.aicodebackend.core.builder.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.UUID;

/**
 * 部署构建任务消息（队列里传的就是它）
 * 消息只带"定位信息 + 排期信息"，不带业务快照：应用名、代码生成类型、deployKey 等一律由消费端
 * 从数据库重新读取。这样排队期间用户改代码 / 重新部署也不会让任务带着过期数据执行……
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeployTaskMessage(Long appId, long sequence, Long operatorId, String taskId, long requestedAt)
        implements Serializable {

    /** 构造任务消息（自动生成 taskId 与当前时间） */
    public static DeployTaskMessage of(Long appId, long sequence, Long operatorId) {
        return of(appId, sequence, operatorId, UUID.randomUUID().toString());
    }

    /**
     * 构造任务消息（指定 taskId，自动补当前时间）
     * 提交部署时 taskId 必须与数据库里的 {@code app.deploy_task_id} 一致，
     * 否则消费端无法判断消息是否属于当前任务。
     */
    public static DeployTaskMessage of(Long appId, long sequence, Long operatorId, String taskId) {
        return new DeployTaskMessage(appId, sequence, operatorId, taskId, System.currentTimeMillis());
    }

    /** 排队时长（毫秒）：消费端打日志用，能直接看出"任务被积压了多久" */
    public long queuedMillis() {
        if (requestedAt <= 0) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - requestedAt);
    }
}
