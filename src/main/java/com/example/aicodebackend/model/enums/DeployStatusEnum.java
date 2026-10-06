package com.example.aicodebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

/**
 * 部署状态枚举（异步部署状态机）
 * <p>
 * 同步部署接口不落状态（一次请求内完成），异步部署接口用它跟踪进度：
 * {@code IDLE -> DEPLOYING -> READY / FAILED}，失败后可重新提交（回到 IDLE）。
 * 数据库里 {@code deploy_status} 为空时按 IDLE 处理，兼容改造前的历史数据。
 */
@Getter
public enum DeployStatusEnum {

    /**
     * 空闲：可提交部署
     */
    IDLE("空闲", "idle"),

    /**
     * 排队中：已受理但还没轮到构建（受构建并发数限制）
     */
    QUEUED("排队中", "queued"),

    /**
     * 部署中：已受理，后台正在构建/发布
     */
    DEPLOYING("部署中", "deploying"),

    /**
     * 部署成功：可直接使用 deployUrl
     */
    READY("部署完成", "ready"),

    /**
     * 部署失败：可携带 errorMessage 重新提交
     */
    FAILED("部署失败", "failed");

    private final String text;

    private final String value;

    DeployStatusEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     *
     * @return 枚举值，不存在或为空时返回 null
     */
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
