package com.example.aicodebackend.ai.provider;

import lombok.Data;
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
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelProperties {

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
     * 取模型配置（null 安全）
     * <p>
     * 手工覆写而不是靠 Lombok 生成：yaml 里写了 {@code models:} 但没写任何子项时，
     * 绑定结果会是 null，后面 {@code forEach} 会直接 NPE，把"少写一层缩进"变成一次启动崩溃。
     *
     * @return 模型配置，永不为 null
     */
    public Map<String, Model> getModels() {
        if (models == null) {
            models = new LinkedHashMap<>();
        }
        return models;
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
     */
    @Data
    public static class Model {

        /**
         * 所属平台（AIProviderEnum 的 value），默认 deepseek
         */
        private String provider = "deepseek";

        /**
         * 接口根地址
         * <p>
         * DeepSeek：完整的 OpenAI 兼容根地址（如 {@code https://api.deepseek.com}）。
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
         * 地域，仅百炼使用，默认华北2（北京）
         */
        private String region = "cn-beijing";

        /**
         * API Key，按平台各自配置（DeepSeek 与百炼的 key 不通用，且百炼的 key 按地域绑定）
         */
        private String apiKey;

        /**
         * 真实调用时传给平台的模型名
         */
        private String modelName;

        /**
         * 单次请求的最大输出 token 数（各平台上限不同，超限会被接口直接拒绝）
         */
        private Integer maxTokens;

        /**
         * 采样温度
         */
        private Double temperature = 0.3;

        /**
         * 请求超时时间
         * <p>
         * 必须显式配置：OpenAiStreamingChatModel 对 connectTimeout / readTimeout 的默认值都是 60 秒
         * （{@code getOrDefault(builder.timeout, ofSeconds(60))}）。思考模式下单个请求很容易超过 60 秒，
         * 一旦被中断就表现为 {@code IOException: closed -> LangChain4jException: closed}。
         */
        private Duration timeout = Duration.ofSeconds(600);

        /**
         * 是否把响应里的 {@code reasoning_content} 解析进 {@code AiMessage.thinking()}
         */
        private boolean returnThinking = true;

        /**
         * 是否在回放历史时把 thinking 作为 {@code reasoning_content} 回传
         * <p>
         * DeepSeek 思考模式带 tools 时，不回传 reasoning_content 会在第二轮起报 400；
         * 百炼（Qwen）接受回传（实测 qwen3.8-max 正常返回），因此两边默认都开。
         */
        private boolean sendThinking = true;

        /**
         * 是否开启思考模式（百炼专属的 {@code enable_thinking} 请求参数）
         * <p>
         * 为 {@code null} 表示不显式传参、沿用平台默认（qwen3.8-max / qwen3.7-plus 默认开启思考）。
         * 关掉可以显著降低延迟与输出 token，代价是复杂代码生成的质量下降。
         */
        private Boolean enableThinking;

        private boolean logRequests = true;

        private boolean logResponses = true;
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
