package com.example.aicodebackend.ai.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 启动期的模型可用性巡检
 * <p>
 * 背景：各平台都会下架 / 改名模型（百炼的模型名还带日期快照版本），而"模型名不存在"这类配置错误
 * 只有在用户真正发起生成时才会暴露成一次 400，排查时要翻日志才知道是模型名的问题。
 * 这里在应用启动后异步拉一次平台的 {@code GET /models}，把"配置里写了但平台已经没有"的模型提前喊出来，
 * 并给出同类可选模型，省掉一次"上线才发现"。
 * <p>
 * 三条硬约束（都是踩过的坑）：
 * <ul>
 *     <li><b>只提示，不阻断</b>：任何失败（没外网、超时、401）都只记日志，绝不抛异常、绝不改任何状态；</li>
 *     <li><b>不拖慢启动</b>：跑在后台（虚拟线程），启动流程不等它；</li>
 *     <li><b>可关</b>：默认关闭，dev 环境显式打开（{@code ai.model-check.enabled: true}）。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.model-check", name = "enabled", havingValue = "true")
public class AiModelAvailabilityChecker implements ApplicationRunner {

    /**
     * 提示同类可选模型时的上限（日志里贴几十个模型名没人看）
     */
    private static final int MAX_SUGGESTIONS = 6;

    private final AiModelProperties properties;

    private final List<StreamingChatModelFactory> factories;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public AiModelAvailabilityChecker(AiModelProperties properties, List<StreamingChatModelFactory> factories) {
        this.properties = properties;
        this.factories = factories;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("ai-model-check").start(() -> {
            try {
                check();
            } catch (Throwable t) {
                // 巡检本身出问题不能影响应用：连日志都只在 debug 级别打完整堆栈
                log.debug("AI 模型可用性巡检异常退出（已忽略）：{}", t.toString());
            }
        });
    }

    /**
     * 按"平台 + 接口地址 + Key"分组核对（同一个平台可能配了不同的业务空间，不能混在一起查）
     */
    void check() {
        Map<String, ProbeGroup> groups = groupByEndpoint();
        if (groups.isEmpty()) {
            return;
        }
        log.info("开始核对 AI 模型可用性（ai.model-check.enabled=true；仅提示，不影响生成）");
        groups.values().forEach(this::checkGroup);
    }

    /**
     * 核对一组（同一个接口地址）配置的模型是否都还在
     *
     * @param group 分组
     */
    private void checkGroup(ProbeGroup group) {
        Set<String> available = fetchModelIds(group.modelsUrl, group.apiKey);
        if (available == null) {
            log.warn("[{}] 无法获取接口 {} 的模型列表（不影响已有模型的使用），跳过可用性核对",
                    group.provider.getText(), group.modelsUrl);
            return;
        }
        log.info("[{}] 接口 {} 当前可用模型 {} 个", group.provider.getText(), group.modelsUrl, available.size());
        group.modelNames.forEach(modelName -> {
            if (available.contains(modelName)) {
                log.info("[{}] 配置的模型 {} 可用", group.provider.getText(), modelName);
            } else {
                log.warn("[{}] 配置的模型 {} 不在接口返回的可用模型列表中，请确认模型名或改用同类模型：{}",
                        group.provider.getText(), modelName, suggest(modelName, available, MAX_SUGGESTIONS));
            }
        });
    }

    /**
     * 拉取平台的模型列表
     * <p>
     * 包内可见：真实调用（{@code BailianStreamingModelIT}）需要直接断言"能拉到平台目录"。
     *
     * @param modelsUrl 模型列表地址
     * @param apiKey    API Key
     *
     * @return 模型 id 集合，拉取失败返回 null
     */
    Set<String> fetchModelIds(String modelsUrl, String apiKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(modelsUrl))
                    .timeout(properties.getModelCheck().getTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("模型列表接口返回 {}：{}", response.statusCode(), StrUtil.maxLength(response.body(), 200));
                return null;
            }
            return parseModelIds(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("模型列表接口调用失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 解析 OpenAI 兼容的 {@code /models} 响应：{@code {"data":[{"id":"..."}]}}
     *
     * @param body 响应体
     *
     * @return 模型 id 集合，结构不符合预期时返回 null
     */
    static Set<String> parseModelIds(String body) {
        try {
            JSONArray data = JSONUtil.parseObj(body).getJSONArray("data");
            if (data == null) {
                return null;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                String id = item == null ? null : item.getStr("id");
                if (StrUtil.isNotBlank(id)) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            log.debug("模型列表响应解析失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 找出与配置模型同族的可选模型
     * <p>
     * 先按"第一个连字符之前"的家族名匹配（{@code qwen3.8-max} → {@code qwen3.8-*}），
     * 家族名里带点的再退一级按主版本匹配（{@code qwen3.8-max} → {@code qwen3*}），
     * 都匹配不到就返回空（宁可不说，也不要给出不相干的模型名）。
     *
     * @param modelName 配置的模型名
     * @param available 平台返回的可用模型
     * @param limit     最多返回多少个
     *
     * @return 建议的模型名列表
     */
    static List<String> suggest(String modelName, Set<String> available, int limit) {
        if (StrUtil.isBlank(modelName) || available == null || available.isEmpty()) {
            return List.of();
        }
        String family = StrUtil.subBefore(modelName, "-", false);
        List<String> candidates = withPrefix(available, family + "-");
        if (candidates.isEmpty() && family.contains(".")) {
            candidates = withPrefix(available, StrUtil.subBefore(family, ".", false));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<String> capped = new ArrayList<>(candidates.subList(0, limit));
        capped.add("...（共 " + candidates.size() + " 个同类可选）");
        return capped;
    }

    /**
     * 取指定前缀的模型名（排序后返回，便于阅读与断言）
     */
    private static List<String> withPrefix(Set<String> available, String prefix) {
        Set<String> matched = new TreeSet<>();
        available.stream().filter(id -> id.startsWith(prefix)).forEach(matched::add);
        return new ArrayList<>(matched);
    }

    /**
     * 按"平台 + 模型列表地址 + Key"把配置里的模型分组
     * <p>
     * 不按平台直接分组：同一平台下不同模型可能指向不同业务空间（不同域名 = 不同模型目录），
     * 混在一起查会把"A 空间没有的模型"误报成"配置错了"。
     *
     * @return 分组结果（key 为分组标识）
     */
    Map<String, ProbeGroup> groupByEndpoint() {
        Map<String, ProbeGroup> groups = new LinkedHashMap<>();
        Map<AIProviderEnum, StreamingChatModelFactory> factoryMap = new LinkedHashMap<>();
        factories.forEach(factory -> factoryMap.put(factory.provider(), factory));

        properties.getModels().forEach((configKey, model) -> {
            if (StrUtil.isBlank(model.getModelName())) {
                return;
            }
            AIProviderEnum provider = AIProviderEnum.getEnumByValue(model.getProvider());
            StreamingChatModelFactory factory = provider == null ? null : factoryMap.get(provider);
            if (factory == null) {
                return;
            }
            String modelsUrl;
            try {
                modelsUrl = factory.modelsUrl(model);
            } catch (Exception e) {
                log.debug("模型 {} 的接口地址无法解析，跳过可用性核对：{}", configKey, e.toString());
                return;
            }
            if (modelsUrl == null || AbstractStreamingChatModelFactory.isUnresolved(model.getApiKey())) {
                return;
            }
            String groupKey = provider.getValue() + "|" + modelsUrl + "|" + model.getApiKey();
            groups.computeIfAbsent(groupKey, key -> new ProbeGroup(provider, modelsUrl, model.getApiKey().trim()))
                    .modelNames.add(model.getModelName().trim());
        });
        return groups;
    }

    /**
     * 一组待核对的模型（同一个接口地址与 Key）
     * <p>
     * 包内可见是为了让单测能直接断言分组结果，不需要真的发 HTTP 请求。
     */
    static final class ProbeGroup {

        final AIProviderEnum provider;

        final String modelsUrl;

        final String apiKey;

        final Set<String> modelNames = new LinkedHashSet<>();

        ProbeGroup(AIProviderEnum provider, String modelsUrl, String apiKey) {
            this.provider = provider;
            this.modelsUrl = modelsUrl;
            this.apiKey = apiKey;
        }
    }
}
