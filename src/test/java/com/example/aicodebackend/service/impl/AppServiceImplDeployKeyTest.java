package com.example.aicodebackend.service.impl;

import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.mybatisflex.core.query.QueryCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署 deployKey 抢占（CAS）的并发回归测试
 * 回归背景：旧实现是"先读 deploy_key，为空就生成一个，最后无条件 updateById"……
 */
class AppServiceImplDeployKeyTest {

    /** 模拟 app 表：只保存 deploy_key，且 updateByCondition 严格按"条件是否命中"返回影响行数 */
    private static final class FakeAppTable {

        private final AtomicReference<String> deployKey = new AtomicReference<>(null);

        private final AtomicInteger updateCalls = new AtomicInteger();

        private final Set<String> attemptedKeys = Collections.synchronizedSet(new HashSet<>());

        /** 模拟 MyBatis-Flex 的条件更新：只有条件命中（deploy_key 仍为 NULL）才写入并返回 1 */
        int updateByCondition(App entity, QueryCondition condition) {
            updateCalls.incrementAndGet();
            String candidate = entity == null ? null : entity.getDeployKey();
            if (candidate != null) {
                attemptedKeys.add(candidate);
            }
            // 条件里包含 "deploy_key IS NULL"，这里用 CAS 语义等价实现
            String current = deployKey.get();
            if (current != null) {
                // 条件不命中：影响 0 行
                return 0;
            }
            String value = entity == null ? null : entity.getDeployKey();
            if (value == null) {
                return 0;
            }
            if (deployKey.compareAndSet(null, value)) {
                return 1;
            }
            return 0;
        }

        String getDeployKey() {
            return deployKey.get();
        }
    }

    @Test
    @DisplayName("并发抢占 deployKey：只有一个 key 落库，其余请求复用同一个 key")
    void concurrentClaimShouldProduceSingleDeployKey() throws Exception {
        int concurrency = 16;
        FakeAppTable table = new FakeAppTable();
        AppServiceImpl appService = newAppService(table);
        Long appId = 123456789L;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<AtomicReference<String>> results = new ArrayList<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            AtomicReference<String> result = new AtomicReference<>();
            results.add(result);
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 并发调用私有抢占逻辑，等价于并发部署请求进入临界区
                String key = (String) ReflectionTestUtils.invokeMethod(appService, "claimDeployKey", appId, null);
                result.set(key);
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未全部就绪");
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        Set<String> distinctKeys = new HashSet<>();
        for (AtomicReference<String> result : results) {
            assertNotNull(result.get(), "抢占结果不应为空");
            distinctKeys.add(result.get());
        }
        assertEquals(1, distinctKeys.size(), "并发抢占产生了多个 deployKey: " + distinctKeys);

        String saved = table.getDeployKey();
        assertNotNull(saved, "deployKey 未能落库");
        assertEquals(saved, distinctKeys.iterator().next(), "返回值与落库值不一致");

        assertTrue(table.attemptedKeys.contains(saved), "落库的 key 不在生成的候选集中");
        assertTrue(table.updateCalls.get() >= 1, "未发生条件更新");
    }

    @Test
    @DisplayName("已有 deployKey 时直接复用，不再生成新 key")
    void existingDeployKeyShouldBeReused() {
        FakeAppTable table = new FakeAppTable();
        AppServiceImpl appService = newAppService(table);

        String key = (String) ReflectionTestUtils.invokeMethod(appService, "claimDeployKey", 1L, "abc123");

        assertEquals("abc123", key, "已有 deployKey 应被直接复用");
        assertEquals(0, table.updateCalls.get(), "复用时不应再次写库");
    }

    @Test
    @DisplayName("抢占失败但数据库已有值时，复用数据库值（幂等）")
    void shouldFallbackToDatabaseValueWhenCasMisses() {
        FakeAppTable table = new FakeAppTable();
        // 模拟"另一个请求已经抢先写入"
        ReflectionTestUtils.setField(table, "deployKey", new AtomicReference<>("zzz999"));
        AppServiceImpl appService = newAppService(table);

        String key = (String) ReflectionTestUtils.invokeMethod(appService, "claimDeployKey", 2L, null);

        assertEquals("zzz999", key, "CAS 未命中时应复用数据库里的 deployKey");
    }

    /**
     * 构造一个注入了假 AppMapper 的 AppServiceImpl
     * AppServiceImpl 是 ServiceImpl 的子类，Mapper 由 MyBatis-Flex 注入……
     */
    private AppServiceImpl newAppService(FakeAppTable table) {
        AppServiceImpl appService = new AppServiceImpl();
        AppMapper mapper = (AppMapper) Proxy.newProxyInstance(
                AppMapper.class.getClassLoader(),
                new Class<?>[]{AppMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "updateByCondition" -> table.updateByCondition((App) args[0], (QueryCondition) args[1]);
                    case "selectOneById" -> {
                        String key = table.getDeployKey();
                        if (key == null) {
                            yield null;
                        }
                        App app = new App();
                        app.setId((Long) args[0]);
                        app.setDeployKey(key);
                        yield app;
                    }
                    case "toString" -> "FakeAppMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        ReflectionTestUtils.setField(appService, "mapper", mapper);
        return appService;
    }
}
