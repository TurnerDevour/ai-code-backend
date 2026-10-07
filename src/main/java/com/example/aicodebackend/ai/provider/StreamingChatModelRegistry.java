package com.example.aicodebackend.ai.provider;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.enums.AIProviderEnum;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 模型注册表：按 {@link AIModelTypeEnum} 提供已构建好的流式模型
 * <p>
 * 取代原来"每个模型一个 @Bean + 工厂构造函数里手工 put 进 Map"的写法：那种写法每加一个模型都要
 * 改工厂的构造函数，模型一多就是个"改一处漏一处"的坑；而且容器里会同时存在多个
 * {@link StreamingChatModel} Bean，只能靠 {@code @Qualifier} 逐个精确注入。
 * <p>
 * 现在的取用链路是固定的：{@code AIModelTypeEnum.value} == 配置 key {@code ai.models.<value>}
 * → 配置里的 {@code provider} 决定用哪个 {@link StreamingChatModelFactory} → 构建出的模型缓存在这里。
 * <p>
 * 容错策略：单个模型配置错（缺 Key、地址写错）只让该模型不可用并打出明确原因，
 * 不影响其它模型，也不阻止应用启动——真正被选中时才抛业务异常（见 {@link #get}）。
 * 这样"某个平台的环境变量没配"不会导致整个服务起不来。
 */
@Slf4j
@Component
public class StreamingChatModelRegistry {

    /**
     * 模型类型 -> 已构建的流式模型
     */
    private final Map<AIModelTypeEnum, StreamingChatModel> streamingChatModels;

    public StreamingChatModelRegistry(AiModelProperties properties, List<StreamingChatModelFactory> factories) {
        Map<AIProviderEnum, StreamingChatModelFactory> factoryMap = new EnumMap<>(AIProviderEnum.class);
        for (StreamingChatModelFactory factory : factories) {
            factoryMap.put(factory.provider(), factory);
        }

        Map<AIModelTypeEnum, StreamingChatModel> built = new EnumMap<>(AIModelTypeEnum.class);
        Map<String, String> failures = new LinkedHashMap<>();
        properties.getModels().forEach((configKey, model) -> buildOne(configKey, model, factoryMap, built, failures));

        reportMissingModels(built, failures);
        this.streamingChatModels = Collections.unmodifiableMap(built);
        log.info("AI 模型注册表就绪：可用 {}，不可用 {}，可用模型={}", built.size(), failures.size(),
                built.keySet().stream().map(AIModelTypeEnum::getValue).collect(Collectors.toList()));
    }

    /**
     * 构建单个模型，失败只记录不抛出
     *
     * @param configKey  配置 key（应与 AIModelTypeEnum.value 一致）
     * @param model      模型配置
     * @param factoryMap 平台 -> 工厂
     * @param built      构建成功的模型收集容器
     * @param failures   失败原因收集容器
     */
    private void buildOne(String configKey,
                          AiModelProperties.Model model,
                          Map<AIProviderEnum, StreamingChatModelFactory> factoryMap,
                          Map<AIModelTypeEnum, StreamingChatModel> built,
                          Map<String, String> failures) {
        AIModelTypeEnum modelType = AIModelTypeEnum.getEnumByValue(configKey);
        if (modelType == null) {
            log.warn("ai.models.{} 不是已注册的模型类型，已忽略；可选值：{}", configKey, registeredValues());
            return;
        }
        AIProviderEnum provider = AIProviderEnum.getEnumByValue(model.getProvider());
        if (provider == null) {
            failures.put(configKey, "未知平台 provider=" + model.getProvider());
            log.error("ai.models.{}.provider={} 不是支持的平台，该模型不可用；可选值：{}",
                    configKey, model.getProvider(), supportedProviders());
            return;
        }
        StreamingChatModelFactory factory = factoryMap.get(provider);
        if (factory == null) {
            failures.put(configKey, "平台 " + provider.getValue() + " 没有对应的模型工厂");
            log.error("平台 {} 没有对应的模型工厂，ai.models.{} 不可用", provider.getValue(), configKey);
            return;
        }
        try {
            built.put(modelType, factory.create(model));
        } catch (Exception e) {
            failures.put(configKey, e.getMessage());
            log.error("AI 模型 {}（{}）初始化失败，选中该模型时会直接报错：{}",
                    configKey, provider.getText(), e.getMessage());
        }
    }

    /**
     * 汇总"枚举里有、但没有可用配置"的模型，避免用户选了之后才发现没配
     *
     * @param built    构建成功的模型
     * @param failures 构建失败的模型（已单独报错，这里不重复）
     */
    private void reportMissingModels(Map<AIModelTypeEnum, StreamingChatModel> built, Map<String, String> failures) {
        List<String> missing = Arrays.stream(AIModelTypeEnum.values())
                .filter(type -> !built.containsKey(type) && !failures.containsKey(type.getValue()))
                .map(AIModelTypeEnum::getValue)
                .toList();
        if (!missing.isEmpty()) {
            log.info("以下模型类型没有配置（ai.models.<模型名> 缺失），被选中时会报错：{}", missing);
        }
    }

    /**
     * 取模型，模型不存在时抛出带排查指引的业务异常
     *
     * @param modelType 模型类型
     *
     * @return 流式模型
     *
     * @throws BusinessException 模型未配置或初始化失败
     */
    public StreamingChatModel get(AIModelTypeEnum modelType) {
        StreamingChatModel model = modelType == null ? null : streamingChatModels.get(modelType);
        if (model == null) {
            String value = modelType == null ? "null" : modelType.getValue();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 模型不可用：" + value + "。请检查配置项 ai.models." + value
                            + "（provider / base-url / api-key / model-name 是否齐全，环境变量是否已配置并重启）");
        }
        return model;
    }

    /**
     * 模型是否可用（不抛异常，供状态查询与巡检使用）
     *
     * @param modelType 模型类型
     *
     * @return 可用返回 true
     */
    public boolean isAvailable(AIModelTypeEnum modelType) {
        return modelType != null && streamingChatModels.containsKey(modelType);
    }

    /**
     * 当前可用的模型类型集合
     *
     * @return 只读集合
     */
    public Set<AIModelTypeEnum> availableModelTypes() {
        return streamingChatModels.keySet();
    }

    /**
     * 已注册的模型类型（用于错误提示）
     *
     * @return 模型标识列表
     */
    private static List<String> registeredValues() {
        return Arrays.stream(AIModelTypeEnum.values()).map(AIModelTypeEnum::getValue).toList();
    }

    /**
     * 已实现的平台（用于错误提示）
     *
     * @return 平台标识列表
     */
    private static List<String> supportedProviders() {
        return Arrays.stream(AIProviderEnum.values()).map(AIProviderEnum::getValue).toList();
    }
}
