package com.example.aicodebackend.ai.provider;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置（配置文件 {@code ai.*}）
 * 改造原因：原来所有模型的配置都塞在 {@code langchain4j.open-ai.models} 下，并且由
 * {@code AiModelConfig} 直接写死"一个模型一个 @Bean"。DeepSeek 是唯一平台时没问题……
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelProperties {

    /**
     * 所有模型共用的默认配置（可选）
     * 与 {@link Model} 同一套字段；这里写的值会补到"没有自己写该项"的模型上。
     * 好处是新增模型时不必把 base-url / api-key 抄一遍，改凭据也只需改一处。
     */
    private Model modelDefaults;

    /**
     * 模型类型（AIModelTypeEnum 的 value）-> 模型配置
     * 用 LinkedHashMap：保持配置文件里的书写顺序，启动日志里打印的模型清单与 yaml 一致，便于对照排查。
     */
    private Map<String, Model> models = new LinkedHashMap<>();

    /** 启动期的模型可用性巡检配置 */
    private ModelCheck modelCheck = new ModelCheck();

    /**
     * 取模型配置（null 安全，并已合并 {@code ai.model-defaults}）
     * 手工覆写而不是靠 Lombok 生成：yaml 里写了 {@code models:} 但没写任何子项时，
     * 绑定结果会是 null，后面 {@code forEach} 会直接 NPE，把"少写一层缩进"变成一次启动崩溃……
     */
    public Map<String, Model> getModels() {
        if (models == null) {
            models = new LinkedHashMap<>();
        }
        Model defaults = getModelDefaults();
        models.replaceAll((key, model) -> {
            Model merged = model == null ? new Model() : model;
            merged.applyDefaults(defaults);
            return merged;
        });
        return models;
    }

    /** 取默认配置（null 安全，原因同 {@link #getModels()}） */
    public Model getModelDefaults() {
        if (modelDefaults == null) {
            modelDefaults = new Model();
        }
        return modelDefaults;
    }

    /** 取巡检配置（null 安全，原因同 {@link #getModels()}） */
    public ModelCheck getModelCheck() {
        if (modelCheck == null) {
            modelCheck = new ModelCheck();
        }
        return modelCheck;
    }

    /**
     * 单个模型的配置项
     * 字段刻意做成"跨平台共用 + 平台专属可选"的扁平结构：yaml 里写起来最短，
     * 平台专属字段（{@code workspace-id} / {@code region} / {@code enable-thinking}）只在对应平台上被读取……
     */
    @Data
    public static class Model {

        /** 未配置 {@code region} 时的地域 */
        public static final String DEFAULT_REGION = "cn-beijing";

        /** 未配置 {@code temperature} 时的采样温度 */
        public static final double DEFAULT_TEMPERATURE = 0.3;

        /** 所属平台（AIProviderEnum 的 value）；未配置时回落 {@link AIProviderEnum#BAILIAN} */
        private String provider;

        /**
         * 接口根地址
         * 阿里云百炼：业务空间专属域名（如 {@code https://ws-xxx.cn-beijing.maas.aliyuncs.com}），
         * 缺少 {@code /compatible-mode/v1} 时由 {@link BailianEndpointResolver} 自动补齐……
         */
        private String baseUrl;

        /**
         * 阿里云百炼业务空间 ID（形如 {@code ws-xxxxxxxx}）
         * 只用于百炼：base-url 缺省时拼域名，base-url 已配置时做一次一致性校验
         * （域名里嵌的 workspace 与这里不一致基本都是配置串了，启动期就提醒出来）。
         */
        private String workspaceId;

        /** 地域，仅百炼使用；未配置时回落 {@value #DEFAULT_REGION} */
        private String region;

        /**
         * API Key
         * 百炼的 Key 按地域绑定：域名与 Key 不属于同一地域时，接口只会返回 401
         * {@code Incorrect API key provided}，从字面上看不出是"地域串了"。
         */
        private String apiKey;

        /**
         * 真实调用时传给平台的模型名
         * 不参与默认值继承：它是每个模型的身份，必须自己声明。
         */
        private String modelName;

        /** 单次请求的最大输出 token 数（各平台上限不同，超限会被接口直接拒绝） */
        private Integer maxTokens;

        /** 采样温度；未配置时回落 {@value #DEFAULT_TEMPERATURE} */
        private Double temperature;

        /**
         * 请求超时时间
         * 建议显式配置（或用 {@code ai.model-defaults} 统一配置）：OpenAiStreamingChatModel 对
         * connectTimeout / readTimeout 的默认值都是 60 秒（{@code getOrDefault(builder.timeout, ofSeconds(60))}）……
         */
        private Duration timeout;

        /** 是否把响应里的 {@code reasoning_content} 解析进 {@code AiMessage.thinking()}（未配置视为 true） */
        @Getter(AccessLevel.NONE)
        private Boolean returnThinking;

        /**
         * 是否在回放历史时把 thinking 作为 {@code reasoning_content} 回传（未配置视为 true）
         * 保持 LangChain4j 的默认值，但百炼的模型在 yaml 里统一关掉：思考内容只用于前端展示，
         * 回传既白花输入 token，也容易被网关当成"不支持的历史字段"拒绝（带 tools 的多轮尤其容易踩）。
         */
        @Getter(AccessLevel.NONE)
        private Boolean sendThinking;

        /**
         * 是否开启思考模式（百炼专属的 {@code enable_thinking} 请求参数）
         * 为 {@code null} 表示不显式传参、沿用平台默认（deepseek-v4 系列与 qwen3.8 系列默认开启思考）。
         * 关掉可以显著降低延迟与输出 token，代价是复杂代码生成的质量下降。
         */
        private Boolean enableThinking;

        /**
         * 推理强度（百炼专属的 {@code reasoning_effort} 请求参数）
         * 只有 deepseek-v4 系列支持：由低到高是 {@code low} / {@code high} / {@code max}，默认 {@code high}；
         * 其中 {@code low} 目前仅 deepseek-v4.1-flash 支持（deepseek-v4-pro 传 low 与 high 等价）……
         */
        private String reasoningEffort;

        /**
         * 思考预算（百炼专属的 {@code thinking_budget} 请求参数）
         * 单次请求里最多允许模型用多少 token 做内部推理；为 {@code null} 表示不显式传参、沿用平台默认
         * （即不设上限）。思考模式的模型在"需求做不到 / 需要反复取舍"时很容易陷入长时间自我推敲……
         */
        private Integer thinkingBudget;

        /** 是否打印请求体（未配置视为 true） */
        @Getter(AccessLevel.NONE)
        private Boolean logRequests;

        /** 是否打印响应体（未配置视为 true） */
        @Getter(AccessLevel.NONE)
        private Boolean logResponses;

        /**
         * 用默认值补齐本模型没有配置的项
         * 只填空、不覆盖：模型自己写过的值永远优先；可重复调用（幂等）。
         */
        public void applyDefaults(Model defaults) {
            if (defaults == null) {
                return;
            }
            if (provider == null) {
                provider = defaults.provider;
            }
            if (baseUrl == null) {
                baseUrl = defaults.baseUrl;
            }
            if (workspaceId == null) {
                workspaceId = defaults.workspaceId;
            }
            if (region == null) {
                region = defaults.region;
            }
            if (apiKey == null) {
                apiKey = defaults.apiKey;
            }
            if (maxTokens == null) {
                maxTokens = defaults.maxTokens;
            }
            if (temperature == null) {
                temperature = defaults.temperature;
            }
            if (timeout == null) {
                timeout = defaults.timeout;
            }
            if (returnThinking == null) {
                returnThinking = defaults.returnThinking;
            }
            if (sendThinking == null) {
                sendThinking = defaults.sendThinking;
            }
            if (enableThinking == null) {
                enableThinking = defaults.enableThinking;
            }
            if (reasoningEffort == null) {
                reasoningEffort = defaults.reasoningEffort;
            }
            if (thinkingBudget == null) {
                thinkingBudget = defaults.thinkingBudget;
            }
            if (logRequests == null) {
                logRequests = defaults.logRequests;
            }
            if (logResponses == null) {
                logResponses = defaults.logResponses;
            }
            // modelName 刻意不继承：每个模型必须自己声明模型名
        }

        /** 取所属平台（未配置时回落 {@link AIProviderEnum#BAILIAN}） */
        public String getProvider() {
            return provider == null || provider.isBlank() ? AIProviderEnum.BAILIAN.getValue() : provider;
        }

        /** 取地域（未配置时回落 {@value #DEFAULT_REGION}） */
        public String getRegion() {
            return region == null || region.isBlank() ? DEFAULT_REGION : region;
        }

        /** 取采样温度（未配置时回落 {@value #DEFAULT_TEMPERATURE}） */
        public Double getTemperature() {
            return temperature == null ? Double.valueOf(DEFAULT_TEMPERATURE) : temperature;
        }

        /** 是否解析响应里的 {@code reasoning_content}（未配置视为 true） */
        public boolean isReturnThinking() {
            return returnThinking == null || returnThinking;
        }

        /** 是否回传历史 thinking（未配置视为 true，与 LangChain4j 默认一致） */
        public boolean isSendThinking() {
            return sendThinking == null || sendThinking;
        }

        /** 是否打印请求体（未配置视为 true） */
        public boolean isLogRequests() {
            return logRequests == null || logRequests;
        }

        /** 是否打印响应体（未配置视为 true） */
        public boolean isLogResponses() {
            return logResponses == null || logResponses;
        }
    }

    /** 模型可用性巡检 */
    @Data
    public static class ModelCheck {

        /**
         * 是否在启动后异步核对"配置里的模型名是否真的存在"
         * 默认关闭：它需要访问外网，且只是提醒，不应该影响任何主流程。
         */
        private boolean enabled = false;

        /** 拉取模型列表的超时时间（巡检本身也必须有时限，否则线程会挂在网络上） */
        private Duration timeout = Duration.ofSeconds(5);
    }
}
