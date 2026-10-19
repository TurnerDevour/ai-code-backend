package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 部署状态枚举（部署状态机）
 * 同步与异步部署共用同一套状态：{@code IDLE -> DEPLOYING -> READY / FAILED}；
 * 异步部署额外有 {@code QUEUED}（已入队、等待构建并发），同步部署在拿到构建额度后会先抢占……
 */
@Getter
public enum DeployStatusEnum {

    /** 空闲：可提交部署 */
    IDLE("空闲", "idle", "尚未部署，可提交部署任务"),

    /** 排队中：已受理但还没轮到构建（受构建并发数限制） */
    QUEUED("排队中", "queued", "已进入部署队列，等待构建中"),

    /** 部署中：已受理，后台正在构建/发布 */
    DEPLOYING("部署中", "deploying", "部署中，请稍后轮询部署状态"),

    /** 部署成功：可直接使用 deployUrl */
    READY("部署完成", "ready", "部署完成，可直接访问"),

    /** 部署失败：可携带 errorMessage 重新提交 */
    FAILED("部署失败", "failed", "部署失败，可修改代码后重新提交部署");

    private final String text;

    private final String value;

    /** 面向用户的默认状态说明 */
    private final String message;

    DeployStatusEnum(String text, String value, String message) {
        this.text = text;
        this.value = value;
        this.message = message;
    }

    /** 根据 value 获取枚举 */
    public static DeployStatusEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (DeployStatusEnum anEnum : DeployStatusEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
