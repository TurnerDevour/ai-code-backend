package com.example.aicodebackend.ai.provider;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 阿里云百炼接口地址解析
 * <p>
 * 百炼是最容易配错的平台，原因在于官方给的是三种"半成品"地址，而不是一个直接用就行根地址：
 * <ul>
 *     <li><b>OpenAI SDK / LangChain4j 用的 base_url</b>：{@code https://{WorkspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1}
 *     —— 业务空间 ID 嵌在域名里，路径固定是 {@code /compatible-mode/v1}；</li>
 *     <li><b>HTTP 直接调用用的 endpoint</b>：在上面基础上再加 {@code /chat/completions}
 *     —— 有人会把整条 endpoint 当成 base_url 填进来，LangChain4j 自己还会再拼一次，必须先把后缀剥掉；</li>
 *     <li><b>控制台里的"业务空间详情"</b>：只给一个 {@code ws-xxxxxxxx}，需要配合地域拼出域名。</li>
 * </ul>
 * 另外百炼对华北2（北京）等地域提供的是"业务空间专属域名"，公共域名
 * （{@code https://dashscope.aliyuncs.com/compatible-mode/v1}）仍可用但不推荐；
 * 而 API Key 是<b>按地域绑定</b>的，域名与 key 不匹配时接口只会回一个 401
 * {@code Incorrect API key provided}，从字面上完全看不出是"地域串了"。
 * 因此这里除了拼地址，还会在启动期把"域名里的 workspace 与配置的 workspace-id 不一致"
 * 这种情况直接告警出来，省掉一次无头绪的排查。
 */
@Slf4j
@Component
public class BailianEndpointResolver {

    /**
     * 百炼 OpenAI 兼容接口的固定路径
     */
    public static final String COMPATIBLE_PATH = "/compatible-mode/v1";

    /**
     * 聊天补全的完整路径后缀（配成 endpoint 时要剥掉，否则会被拼两次）
     */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 业务空间专属域名的后缀
     */
    private static final String WORKSPACE_DOMAIN_SUFFIX = ".maas.aliyuncs.com";

    /**
     * 默认地域：华北2（北京）
     */
    public static final String DEFAULT_REGION = "cn-beijing";

    /**
     * 公共域名（没有业务空间信息时的兜底，仍可调用，只是没有专属域名的稳定性）
     */
    public static final String PUBLIC_BASE_URL = "https://dashscope.aliyuncs.com" + COMPATIBLE_PATH;

    /**
     * 解析出可直接交给 LangChain4j 的 base_url
     *
     * @param properties 单模型配置
     *
     * @return 以 {@code /compatible-mode/v1} 结尾的 base_url
     */
    public String resolve(AiModelProperties.Model properties) {
        String configuredBaseUrl = normalize(properties.getBaseUrl());
        String workspaceId = normalize(properties.getWorkspaceId());
        if (configuredBaseUrl != null) {
            String resolved = toCompatibleBaseUrl(configuredBaseUrl);
            checkWorkspaceConsistency(resolved, workspaceId);
            return resolved;
        }
        if (workspaceId != null) {
            String region = StrUtil.blankToDefault(normalize(properties.getRegion()), DEFAULT_REGION);
            String resolved = "https://" + workspaceId + "." + region + WORKSPACE_DOMAIN_SUFFIX + COMPATIBLE_PATH;
            log.info("百炼未配置 base-url，按业务空间信息拼出接口地址：{}", resolved);
            return resolved;
        }
        log.warn("百炼既没有配置 base-url 也没有配置 workspace-id，回落到公共域名 {}"
                + "（建议改用业务空间专属域名，稳定性和性能更好）", PUBLIC_BASE_URL);
        return PUBLIC_BASE_URL;
    }

    /**
     * 把配置里的地址补成"OpenAI SDK 形式的 base_url"
     * <p>
     * 幂等：已经是 {@code /compatible-mode/v1} 结尾时原样返回，可以安全地重复调用。
     *
     * @param rawBaseUrl 配置值
     *
     * @return 规范化后的 base_url
     */
    public String toCompatibleBaseUrl(String rawBaseUrl) {
        String url = rawBaseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(CHAT_COMPLETIONS_PATH)) {
            url = url.substring(0, url.length() - CHAT_COMPLETIONS_PATH.length());
        }
        if (url.endsWith(COMPATIBLE_PATH)) {
            return url;
        }
        if (url.endsWith("/compatible-mode")) {
            return url + "/v1";
        }
        return url + COMPATIBLE_PATH;
    }

    /**
     * 域名与 workspace-id 的一致性校验（只告警，不阻断）
     * <p>
     * 这里刻意不抛异常：域名是可用的（能通就能跑），只是"配错了另一套"，属于提醒级别；
     * 一旦抛异常就会把整个应用拖起来不了，代价远大于收益。
     *
     * @param baseUrl     已解析的 base_url
     * @param workspaceId 配置的业务空间 ID
     */
    private void checkWorkspaceConsistency(String baseUrl, String workspaceId) {
        if (workspaceId == null) {
            return;
        }
        String host = hostOf(baseUrl);
        if (host == null) {
            return;
        }
        if (!host.endsWith(WORKSPACE_DOMAIN_SUFFIX)) {
            log.warn("百炼 base-url 的域名 {} 不是业务空间专属域名（应形如 https://{workspaceId}.{region}{}），"
                    + "配置的 workspace-id={} 未参与拼接；public 域名仍可用，但建议迁移到专属域名",
                    host, WORKSPACE_DOMAIN_SUFFIX, workspaceId);
            return;
        }
        String hostWorkspaceId = host.substring(0, host.length() - WORKSPACE_DOMAIN_SUFFIX.length());
        if (!workspaceId.equalsIgnoreCase(hostWorkspaceId)) {
            log.warn("百炼 base-url 的域名属于业务空间 {}，但配置的 workspace-id 是 {}，两者不一致；"
                    + "另外 API Key 按地域绑定，跨地域调用只会返回 401 Incorrect API key provided，请核对配置来源",
                    hostWorkspaceId, workspaceId);
        }
    }

    /**
     * 取域名（形如 {@code ws-xxx.cn-beijing.maas.aliyuncs.com}）
     *
     * @param url 地址
     *
     * @return 域名，解析失败返回 null
     */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            log.warn("百炼 base-url 无法解析出域名，跳过一致性校验：{}", url);
            return null;
        }
    }

    /**
     * 归一化配置值：空白与"未解析的环境变量占位符"都视为没配
     *
     * @param value 配置值
     *
     * @return 归一化后的值，或 null
     */
    private static String normalize(String value) {
        if (AbstractStreamingChatModelFactory.isUnresolved(value)) {
            return null;
        }
        return value.trim();
    }
}
