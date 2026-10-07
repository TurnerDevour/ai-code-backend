package com.example.aicodebackend.ai.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼接口地址解析测试
 * <p>
 * 这一层最容易出错也最难排查：地址拼错的表现是 404 / 401，而官方文档里同时存在
 * "OpenAI SDK 用的 base_url"和"HTTP 直接调用用的 endpoint"两种写法，业务空间 ID 又嵌在域名里。
 * 用例覆盖真实会遇到的几种配置形态。
 */
class BailianEndpointResolverTest {

    private final BailianEndpointResolver resolver = new BailianEndpointResolver();

    private static AiModelProperties.Model model(String baseUrl, String workspaceId) {
        AiModelProperties.Model model = new AiModelProperties.Model();
        model.setBaseUrl(baseUrl);
        model.setWorkspaceId(workspaceId);
        model.setModelName("qwen3.8-max");
        return model;
    }

    /** 环境变量里给的就是业务空间专属域名（不带 /compatible-mode/v1），必须自动补齐 */
    @Test
    void shouldAppendCompatiblePathToWorkspaceDomain() {
        String resolved = resolver.resolve(model("https://ws-oo5tqem2rjnxpmxj.cn-beijing.maas.aliyuncs.com", "ws-oo5tqem2rjnxpmxj"));
        assertEquals("https://ws-oo5tqem2rjnxpmxj.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", resolved);
    }

    /** 已经是完整的 base_url 时保持幂等（重复解析不能拼成 .../compatible-mode/v1/compatible-mode/v1） */
    @Test
    void shouldKeepAlreadyCompatibleBaseUrl() {
        String baseUrl = "https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
        assertEquals(baseUrl, resolver.resolve(model(baseUrl, "ws-abc")));
        assertEquals(baseUrl, resolver.resolve(model(baseUrl + "/", "ws-abc")));
    }

    /** 有人会把 HTTP endpoint（.../chat/completions）整条填进 base-url，必须剥掉后缀，否则会被拼两次 */
    @Test
    void shouldStripChatCompletionsSuffix() {
        String resolved = resolver.resolve(model(
                "https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions", "ws-abc"));
        assertEquals("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", resolved);
    }

    /** 只配到 /compatible-mode 时补上版本号 */
    @Test
    void shouldAppendVersionToCompatibleModeOnly() {
        assertEquals("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                resolver.resolve(model("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode", "ws-abc")));
    }

    /** 没配 base-url 时用 workspace-id + region 拼 */
    @Test
    void shouldBuildFromWorkspaceIdAndRegion() {
        AiModelProperties.Model model = model(null, "ws-oo5tqem2rjnxpmxj");
        model.setRegion("cn-beijing");
        assertEquals("https://ws-oo5tqem2rjnxpmxj.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                resolver.resolve(model));
    }

    /** region 缺省时按华北2（北京）处理 */
    @Test
    void shouldFallbackToDefaultRegion() {
        AiModelProperties.Model model = model(null, "ws-abc");
        model.setRegion("   ");
        assertTrue(resolver.resolve(model).contains(".cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
    }

    /** 环境变量没配时 Spring 会原样保留 ${...}，不能拿它去拼地址 */
    @Test
    void shouldTreatUnresolvedPlaceholderAsMissing() {
        AiModelProperties.Model model = model("${ALI_AI_BASE_URL}", "${ALI_WORKSPACE_ID}");
        assertEquals(BailianEndpointResolver.PUBLIC_BASE_URL, resolver.resolve(model));
    }

    /** 什么都没有时回落到公共域名（仍可调用，只是没有专属域名的稳定性） */
    @Test
    void shouldFallbackToPublicBaseUrl() {
        assertEquals(BailianEndpointResolver.PUBLIC_BASE_URL, resolver.resolve(model(null, null)));
    }

    /** 域名与 workspace-id 不一致只告警、不影响解析结果（不能因此让应用起不来） */
    @Test
    void shouldNotFailWhenWorkspaceIdMismatchesDomain() {
        assertEquals("https://ws-in-domain.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                resolver.resolve(model("https://ws-in-domain.cn-beijing.maas.aliyuncs.com", "ws-configured")));
    }
}
