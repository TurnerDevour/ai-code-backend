package com.example.aicodebackend.config;

import com.example.aicodebackend.ai.AiCodeGeneratorService;
import com.example.aicodebackend.model.enums.AIModelTypeEnum;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 生成服务缓存失效（记忆损坏后的自愈入口）测试
 * <p>
 * 背景：一次生成因"assistant 消息带 tool_calls 却没有工具结果"失败后，缓存里的生成服务实例
 * 仍持有损坏的记忆快照；必须把该应用的缓存条目清掉，下一次请求才会按（读取时已清洗的）记忆重建。
 */
class AiCodeGeneratorServiceCacheTest {

    /** 只用于往缓存里塞占位对象的空实现 */
    private static final AiCodeGeneratorService DUMMY = new AiCodeGeneratorService() {
        @Override
        public reactor.core.publisher.Flux<String> generateHTMLCodeStream(String prompt) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Flux<String> generateMultipleFileCodeStream(String prompt) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public dev.langchain4j.service.TokenStream generateVueProjectCodeStream(long appId, String prompt) {
            return null;
        }

        @Override
        public reactor.core.publisher.Flux<String> repairGeneratedCodeStream(String prompt) {
            return reactor.core.publisher.Flux.empty();
        }
    };

    /**
     * 往缓存里塞测试数据
     * <p>
     * 缓存实现类是包内私有类，反射调用其接口方法时需要显式放开访问权限。
     */
    private static void put(AiCodeGeneratorServiceFactory factory, long appId, CodeGenTypeEnum type) throws Exception {
        java.lang.reflect.Field field = AiCodeGeneratorServiceFactory.class.getDeclaredField("serviceCache");
        field.setAccessible(true);
        Object cache = field.get(factory);
        String key = appId + "_" + type.getValue() + "_" + AIModelTypeEnum.DEEPSEEK_FLASH.getValue();
        Method put = cache.getClass().getMethod("put", Object.class, Object.class);
        put.setAccessible(true);
        put.invoke(cache, key, DUMMY);
    }

    @SuppressWarnings("unchecked")
    private static long size(AiCodeGeneratorServiceFactory factory) throws Exception {
        java.lang.reflect.Field field = AiCodeGeneratorServiceFactory.class.getDeclaredField("serviceCache");
        field.setAccessible(true);
        Object cache = field.get(factory);
        return ((com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache).estimatedSize();
    }

    @Test
    void shouldInvalidateOnlyTargetAppEntries() throws Exception {
        AiCodeGeneratorServiceFactory factory = new AiCodeGeneratorServiceFactory(null, null, null, null);
        put(factory, 1001L, CodeGenTypeEnum.VUE_PROJECT);
        put(factory, 1001L, CodeGenTypeEnum.HTML);
        put(factory, 1002L, CodeGenTypeEnum.VUE_PROJECT);
        assertEquals(3, size(factory));

        int invalidated = factory.invalidateAiCodeGeneratorService(1001L);

        assertEquals(2, invalidated, "只应失效目标应用的条目");
        assertEquals(1, size(factory), "另一个应用的缓存必须保留");
    }

    @Test
    void shouldBeIdempotentWhenNothingCached() {
        AiCodeGeneratorServiceFactory factory = new AiCodeGeneratorServiceFactory(null, null, null, null);
        assertEquals(0, factory.invalidateAiCodeGeneratorService(9999L));
    }

    /**
     * 只有"tool_calls / tool messages"这类记忆损坏才触发失效
     */
    @Test
    void shouldDetectToolMessagesInvalidError() throws Exception {
        AiCodeGeneratorServiceFactory factory = new AiCodeGeneratorServiceFactory(null, null, null, null);
        put(factory, 2001L, CodeGenTypeEnum.VUE_PROJECT);

        com.example.aicodebackend.core.AiCodeGeneratorFacade facade =
                new com.example.aicodebackend.core.AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        Method invalidate = com.example.aicodebackend.core.AiCodeGeneratorFacade.class
                .getDeclaredMethod("invalidateServiceCacheIfToolMessagesInvalid", Throwable.class, Long.class);
        invalidate.setAccessible(true);

        // 无关错误：不动缓存
        invalidate.invoke(facade, new IllegalStateException("network down"), 2001L);
        assertEquals(1, size(factory), "无关异常不应清缓存");

        // 真实错误（带嵌套 cause）：清缓存
        Throwable apiError = new RuntimeException(
                "{\"error\":{\"message\":\"An assistant message with 'tool_calls' must be followed by "
                        + "tool messages responding to each 'tool_call_id'. (insufficient tool messages following "
                        + "tool_calls message)\"}}");
        invalidate.invoke(facade, new RuntimeException("AI回复失败", apiError), 2001L);
        assertEquals(0, size(factory), "记忆损坏异常应清掉该应用的生成服务缓存");
    }
}
