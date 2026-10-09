package com.example.aicodebackend.core.generation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 「思考跑飞」守卫的阈值配置（配置文件 {@code ai.thinking-guard.*}）
 * <p>
 * 默认<b>不配也能跑</b>：阈值由所选模型在平台侧的 {@code thinking-budget} 推导
 * （见 {@link ThinkingRunawayGuard.Limits#forThinkingBudget}），保证守卫永远比平台预算宽松。
 * 这里只提供"手工覆盖"的口子，用于两种情况：
 * <ul>
 *     <li>平台不按 {@code thinking-budget} 截断（推理真的会无穷无尽），需要比推导值更早掐断；</li>
 *     <li>链路很慢或模型很啰嗦，推导值仍然不够宽。</li>
 * </ul>
 * 覆盖值只影响守卫（第二道防线），不影响平台预算本身。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.thinking-guard")
public class ThinkingGuardProperties {

    /**
     * 无进展思考的字符数上限；{@code null}（不配）表示按所选模型的思考预算推导
     */
    private Integer maxChars;

    /**
     * 无进展思考的时长上限；{@code null}（不配）表示按所选模型的思考预算推导
     */
    private Duration maxDuration;

    /**
     * 算出本轮实际使用的阈值：先按预算推导，再用配置里的覆盖值盖掉对应项
     *
     * @param thinkingBudgetTokens 本轮所选模型的 {@code thinking-budget}（可为 null）
     *
     * @return 阈值
     */
    public ThinkingRunawayGuard.Limits limitsFor(Integer thinkingBudgetTokens) {
        return ThinkingRunawayGuard.Limits.forThinkingBudget(thinkingBudgetTokens)
                .withOverrides(maxChars, maxDuration);
    }
}
