package com.example.aicodebackend.ai.provider;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置（配置文件 {@code ai.*}）
 * <p>
 * 改造原因：原来所有模型的配置都塞在 {@code langchain4j.open-ai.models} 下，并且由
 * {@code AiModelConfig} 直接写死"一个模型一个 @Bean"。DeepSeek 是唯一平台时没问题，
 * 再接一个平台（阿里云百炼）就会出现"新加一个模型要改 4 个文件（yaml + 枚举 + @Bean + 工厂构造函数）"
 * 的扩散式改动，而且平台之间的差异（接口路径、思考模式开关）无处安放。
 * <p>
 * 现在配置只描述"有哪些模型"，"怎么建模型"交给各平台的
 * {@link StreamingChatModelFactory}，"按模型类型取模型"交给 {@link StreamingChatModelRegistry}。
 * <p>
 * 配置约定：{@code ai.models.<key>} 的 key 必须与 {@code AIModelTypeEnum.value} 完全一致
 * （它同时是接口参数与数据库取值）。key 里带 {@code .} 的模型（如 {@code qwen3.8-max}）
 * 在 YAML 里必须写成 {@code "[qwen3.8-max]"}：Spring 的宽松绑定会把未转义的 {@code .}
 * 当成层级分隔符，导致 key 被解析成 {@code qwen3} → {@code 8-max} 而绑定不上。
 * <p>
 * 配置分层：{@code ai.model-defaults} 放"所有模型共用"的项（同一个业务空间的 base-url / workspace-id /
 * api-key、统一的超时与日志开关等），{@code ai.models.<key>} 只写这个模型自己的差异
 * （{@code model-name}、{@code max-tokens} 等）。两者在 {@link #getModels()} 里合并：
 * <b>只填空、不覆盖</b>，模型自己写过的值永远优先；{@code model-name} 不参与继承
 * （它是模型的身份，必须自己声明）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelProperties {

    /**
     * 所有模型共用的默认配置（可选）
     * <p>
     * 与 {@link Model} 同一套字段；这里写的值会补到"没有自己写该项"的模型上。
     * 好处是新增模型时不必把 base-url / api-key 抄一遍，改凭据也只需改一处。
     */
    private Model modelDefaults;

    /**
     * 模型类型（AIModelTypeEnum 的 value）-> 模型配置
     * <p>
     * 用 LinkedHashMap：保持配置文件里的书写顺序，启动日志里打印的模型清单与 yaml 一致，便于对照排查。
     */
    private Map<String, Model> models = new LinkedHashMap<>();

    /**
     * 启动期的模型可用性巡检配置
     */
    private ModelCheck modelCheck = new ModelCheck();

    /**
     * 取模型配置（null 安全，并已合并 {@code ai.model-defaults}）
     * <p>
     * 手工覆写而不是靠 Lombok 生成：yaml 里写了 {@code models:} 但没写任何子项时，
     * 绑定结果会是 null，后面 {@code forEach} 会直接 NPE，把"少写一层缩进"变成一次启动崩溃。
     * <p>
     * 合并放在这里（取配置的唯一入口）而不是各个调用方：调用方拿到的永远是"已经补齐默认值"的配置，
     * 不会出现"注册表合并了、巡检没合并"这类只在某条路径上生效的偏差。
     * 合并只填空、可重复调用；yaml 里写成 {@code key:}（值为 null）的模型会被换成空对象，
     * 语义就是"这个模型全部走默认值"。
     *
     * @return 模型配置，永不为 null
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

    /**
     * 取默认配置（null 安全，原因同 {@link #getModels()}）
     *
     * @return 默认配置，永不为 null
     */
    public Model getModelDefaults() {
        if (modelDefaults == null) {
            modelDefaults = new Model();
        }
        return modelDefaults;
    }

    /**
     * 取巡检配置（null 安全，原因同 {@link #getModels()}）
     *
     * @return 巡检配置，永不为 null
     */
    public ModelCheck getModelCheck() {
        if (modelCheck == null) {
            modelCheck = new ModelCheck();
        }
        return modelCheck;
    }

    /**
     * 单个模型的配置项
     * <p>
     * 字段刻意做成"跨平台共用 + 平台专属可选"的扁平结构：yaml 里写起来最短，
     * 平台专属字段（{@code workspace-id} / {@code region} / {@code enable-thinking}）只在对应平台上被读取，
     * 用错平台不会静默生效，而是由对应工厂决定是否使用。
     * <p>
     * "没写"与"写了默认值"必须能区分开，{@link #applyDefaults} 才能正确填空，因此：
     * 可继承的开关用包装类型（{@code null} = 没写），字段的兜底值放在 getter / 工厂里
     * （{@code getXxx()} 返回兜底值，字段本身仍可为 null）。
     */
    @Data
    public static class Model {

        /**
         * 未配置 {@code provider} 时回落的平台
         */
        public static final String DEFAULT_PROVIDER = "bailian";

        /**
         * 未配置 {@code region} 时的地域
         */
        public static final String DEFAULT_REGION = "cn-beijing";

        /**
         * 未配置 {@code temperature} 时的采样温度
         */
        public static final double DEFAULT_TEMPERATURE = 0.3;

        /**
         * 所属平台（AIProviderEnum 的 value）；未配置时回落 {@value #DEFAULT_PROVIDER}
         */
        private String provider;

        /**
         * 接口根地址
         * <p>
         * 阿里云百炼：业务空间专属域名（如 {@code https://ws-xxx.cn-beijing.maas.aliyuncs.com}），
         * 缺少 {@code /compatible-mode/v1} 时由 {@link BailianEndpointResolver} 自动补齐；
         * 不配置时回落到"用 workspace-id + region 拼"。
         */
        private String baseUrl;

        /**
         * 阿里云百炼业务空间 ID（形如 {@code ws-xxxxxxxx}）
         * <p>
         * 只用于百炼：base-url 缺省时拼域名，base-url 已配置时做一次一致性校验
         * （域名里嵌的 workspace 与这里不一致基本都是配置串了，启动期就提醒出来）。
         */
        private String workspaceId;

        /**
         * 地域，仅百炼使用；未配置时回落 {@value #DEFAULT_REGION}
         */
        private String region;

        /**
         * API Key
         * <p>
         * 百炼的 Key 按地域绑定：域名与 Key 不属于同一地域时，接口只会返回 401
         * {@code Incorrect API key provided}，从字面上看不出是"地域串了"。
         */
        private String apiKey;

        /**
         * 真实调用时传给平台的模型名
         * <p>
         * 不参与默认值继承：它是每个模型的身份，必须自己声明。
         */
        private String modelName;

        /**
         * 单次请求的最大输出 token 数（各平台上限不同，超限会被接口直接拒绝）
         */
        private Integer maxTokens;

        /**
         * 采样温度；未配置时回落 {@value #DEFAULT_TEMPERATURE}
         */
        private Double temperature;

        /**
         * 请求超时时间
         * <p>
         * 建议显式配置（或用 {@code ai.model-defaults} 统一配置）：OpenAiStreamingChatModel 对
         * connectTimeout / readTimeout 的默认值都是 60 秒（{@code getOrDefault(builder.timeout, ofSeconds(60))}）。
         * 思考模式下单个请求很容易超过 60 秒，一旦被中断就表现为
         * {@code IOException: closed -> LangChain4jException: closed}。
         * 保持 {@code null} 时由 {@code AbstractStreamingChatModelFactory} 兜底到 600 秒并打警告。
         */
        private Duration timeout;

        /**
         * 是否把响应里的 {@code reasoning_content} 解析进 {@code AiMessage.thinking()}（未配置视为 true）
         */
        @Getter(AccessLevel.NONE)
        private Boolean returnThinking;

        /**
         * 是否在回放历史时把 thinking 作为 {@code reasoning_content} 回传（未配置视为 true）
         * <p>
         * 保持 LangChain4j 的默认值，但百炼的模型在 yaml 里统一关掉：思考内容只用于前端展示，
         * 回传既白花输入 token，也容易被网关当成"不支持的历史字段"拒绝（带 tools 的多轮尤其容易踩）。
         */
        @Getter(AccessLevel.NONE)
        private Boolean sendThinking;

        /**
         * 是否开启思考模式（百炼专属的 {@code enable_thinking} 请求参数）
         * <p>
         * 为 {@code null} 表示不显式传参、沿用平台默认（deepseek-v4 系列与 qwen3.8 系列默认开启思考）。
         * 关掉可以显著降低延迟与输出 token，代价是复杂代码生成的质量下降。
         */
        private Boolean enableThinking;

        /**
         * 推理强度（百炼专属的 {@code reasoning_effort} 请求参数）
         * <p>
         * 只有 deepseek-v4 系列支持：由低到高是 {@code low} / {@code high} / {@code max}，默认 {@code high}；
         * 其中 {@code low} 目前仅 deepseek-v4.1-flash 支持（deepseek-v4-pro 传 low 与 high 等价）。
         * 为 {@code null} 或空白表示不显式传参、沿用平台默认。
         */
        private String reasoningEffort;

        /**
         * 思考预算（百炼专属的 {@code thinking_budget} 请求参数）
         * <p>
         * 单次请求里最多允许模型用多少 token 做内部推理；为 {@code null} 表示不显式传参、沿用平台默认
         * （即不设上限）。思考模式的模型在"需求做不到 / 需要反复取舍"时很容易陷入长时间自我推敲，
         * 表现为前端「AI 思考过程」面板永远在往上滚（实测：同一个"找网上真实画像"的请求，
         * 不设预算时思考 10 分钟以上仍不收敛，设成 1024 后同一请求 19 秒内收敛并正常调用工具）。
         * 因此生产配置必须给一个上限，让跑飞的推理被平台侧硬性截断，而不是靠人盯着刷新页面。
         */
        private Integer thinkingBudget;

        /**
         * 是否打印请求体（未配置视为 true）
         */
        @Getter(AccessLevel.NONE)
        private Boolean logRequests;

        /**
         * 是否打印响应体（未配置视为 true）
         */
        @Getter(AccessLevel.NONE)
        private Boolean logResponses;

        /**
         * 用默认值补齐本模型没有配置的项
         * <p>
         * 只填空、不覆盖：模型自己写过的值永远优先；可重复调用（幂等）。
         *
         * @param defaults 默认配置，可为 null（表示没有默认值可继承）
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

        /**
         * 取所属平台（未配置时回落 {@value #DEFAULT_PROVIDER}）
         */
        public String getProvider() {
            return provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider;
        }

        /**
         * 取地域（未配置时回落 {@value #DEFAULT_REGION}）
         */
        public String getRegion() {
            return region == null || region.isBlank() ? DEFAULT_REGION : region;
        }

        /**
         * 取采样温度（未配置时回落 {@value #DEFAULT_TEMPERATURE}）
         */
        public Double getTemperature() {
            return temperature == null ? DEFAULT_TEMPERATURE : temperature;
        }

        /**
         * 是否解析响应里的 {@code reasoning_content}（未配置视为 true）
         */
        public boolean isReturnThinking() {
            return returnThinking == null || returnThinking;
        }

        /**
         * 是否回传历史 thinking（未配置视为 true，与 LangChain4j 默认一致）
         */
        public boolean isSendThinking() {
            return sendThinking == null || sendThinking;
        }

        /**
         * 是否打印请求体（未配置视为 true）
         */
        public boolean isLogRequests() {
            return logRequests == null || logRequests;
        }

        /**
         * 是否打印响应体（未配置视为 true）
         */
        public boolean isLogResponses() {
            return logResponses == null || logResponses;
        }
    }

    /**
     * 模型可用性巡检
     */
    @Data
    public static class ModelCheck {

        /**
         * 是否在启动后异步核对"配置里的模型名是否真的存在"
         * <p>
         * 默认关闭：它需要访问外网，且只是提醒，不应该影响任何主流程。
         */
        private boolean enabled = false;

        /**
         * 拉取模型列表的超时时间（巡检本身也必须有时限，否则线程会挂在网络上）
         */
        private Duration timeout = Duration.ofSeconds(5);
    }
}
