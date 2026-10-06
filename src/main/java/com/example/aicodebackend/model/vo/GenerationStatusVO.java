package com.example.aicodebackend.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 生成任务状态视图（方案 C：生成与客户端连接解耦后的状态查询）
 */
@Data
public class GenerationStatusVO implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 生成状态：running / finished / failed / none
     * <p>
     * none 表示当前没有生成任务（页面刷新后任务已经结束且没有记录时会出现）。
     */
    private String status;

    /**
     * 面向用户的状态说明
     */
    private String message;

    /**
     * 服务端已累积内容的字符数（用于判断"是否已经生成了一些内容"）
     */
    private Integer contentLength;

    /**
     * 已下发的最后一帧序号：前端续订时用它做 fromSeq，避免重复渲染
     */
    private Long lastSeq;

    /**
     * 失败原因（status=failed）
     */
    private String errorMessage;

    /**
     * 是否仍在生成
     */
    private Boolean running;

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构建"没有任务"的状态
     *
     * @param appId 应用 id
     *
     * @return 状态视图
     */
    public static GenerationStatusVO none(Long appId) {
        GenerationStatusVO vo = new GenerationStatusVO();
        vo.setAppId(appId);
        vo.setStatus("none");
        vo.setRunning(false);
        vo.setMessage("当前没有进行中的生成任务");
        vo.setContentLength(0);
        vo.setLastSeq(0L);
        return vo;
    }
}
