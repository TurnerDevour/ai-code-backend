package com.example.aicodebackend.model.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.Data;

/** 应用 */
@Data
@Table(value = "app")
public class App implements Serializable {

    /** id */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /** 应用名称 */
    private String appName;

    /** 应用封面 */
    private String cover;

    /** 应用初始化的 prompt */
    private String initPrompt;

    /** 代码生成类型（枚举） */
    private String codeGenType;

    /** AI 模型类型（枚举） */
    private String aiModelType;

    /** 部署标识 */
    private String deployKey;

    /** 部署时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deployedTime;

    /**
     * 部署状态：idle/queued/deploying/ready/failed
     * 同步与异步部署共用；为空时按 idle 处理，兼容改造前的历史数据。
     */
    private String deployStatus;

    /** 最近一次异步部署的失败原因（面向用户，不含内部堆栈） */
    private String deployError;

    /**
     * 最近一次部署的发起人 id
     * 异步部署需要它：任务在后台线程执行，请求上下文已经结束，无法再从 Session 取登录用户；
     * 同时它也让"部署中"的任务在管理端可追溯。
     */
    private Long deployOperatorId;

    /**
     * 当前部署任务 id
     * 每次提交生成一个随机 token：MQ 消息里带同一个值，消费端凭它做"同任务重投"的幂等判断。
     * 进程崩溃后消息重投时，原来的 queued/deploying 状态和 token 都还在，可以安全地继续执行……
     */
    private String deployTaskId;

    /**
     * 部署任务心跳：构建线程/心跳调度器定期刷新
     * 恢复扫描用它判断 deploying 是否还活着：心跳超过阈值没更新，说明进程已崩溃或任务已消失，
     * 可以安全地落 failed，而不是让应用卡到 30 分钟僵死判定。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deployHeartbeat;

    /** 优先级 */
    private Integer priority;

    /** 创建用户id */
    private Long userId;

    /** 编辑时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime editTime;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /** 是否删除 */
    @Column(isLogicDelete = true)
    private Integer isDelete;

    @Serial
    private static final long serialVersionUID = 1L;
}
