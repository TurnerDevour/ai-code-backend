package com.example.aicodebackend.core.generation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 「思考跑飞」守卫的阈值配置（配置文件 {@code ai.thinking-guard.*}）
 * 默认不配也能跑：阈值由所选模型在平台侧的 {@code thinking-budget} 推导
 * （见 {@link ThinkingRunawayGuard.Limits#forThinkingBudget}），保证守卫永远比平台预算宽松……
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.thinking-guard")
public class ThinkingGuardProperties {

    /** 无进展思考的字符数上限；{@code null}（不配）表示按所选模型的思考预算推导 */
    private Integer maxChars;

    /** 无进展思考的时长上限；{@code null}（不配）表示按所选模型的思考预算推导 */
    private Duration maxDuration;

    /** 算出本轮实际使用的阈值：先按预算推导，再用配置里的覆盖值盖掉对应项 */
    public ThinkingRunawayGuard.Limits limitsFor(Integer thinkingBudgetTokens) {
        return ThinkingRunawayGuard.Limits.forThinkingBudget(thinkingBudgetTokens)
                .withOverrides(maxChars, maxDuration);
    }
}
