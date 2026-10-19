package com.example.aicodebackend.service.impl;

import cn.hutool.core.io.FileUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** 同步部署的同应用锁：必须有界等待，不能把 Web 线程挂到构建结束 */
class AppServiceImplDeployLockTest {

    @Test
    @DisplayName("同应用已有构建时：等锁 1 秒后快速失败（42900），不阻塞到构建结束")
    void sameAppLockShouldFailFast() throws Exception {
        Long appId = 987654321L;
        String codeGenType = CodeGenTypeEnum.HTML.getValue();
        File sourceDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType + "_" + appId);
        Files.createDirectories(sourceDir.toPath());

        App app = new App();
        app.setId(appId);
        app.setUserId(7L);
        app.setCodeGenType(codeGenType);
        app.setDeployStatus(DeployStatusEnum.IDLE.getValue());

        AppServiceImpl appService = spy(new AppServiceImpl());
        doReturn(app).when(appService).getById(appId);
        ReflectionTestUtils.setField(appService, "deployQueueManager", mock(DeployQueueManager.class));

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            AppServiceImpl.LockRef heldRef = AppServiceImpl.acquireDeployLock(appId);
            heldRef.lock.lock();
            try {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                heldRef.lock.unlock();
                AppServiceImpl.releaseDeployLock(appId, heldRef);
            }
        }, "deploy-lock-holder");
        holder.start();
        assertTrue(holding.await(3, TimeUnit.SECONDS), "占锁线程应已启动");
        try {
            User user = new User();
            user.setId(7L);

            long start = System.nanoTime();
            BusinessException error = assertThrows(BusinessException.class,
                    () -> appService.deployApp(appId, user));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), error.getCode());
            assertTrue(error.getMessage().contains("该应用正在部署中"), "实际=" + error.getMessage());
            assertTrue(elapsedMs < 3_000, "等锁应有界（配置 1 秒），实际等待 " + elapsedMs + "ms");
        } finally {
            release.countDown();
            holder.join(5_000);
            FileUtil.del(sourceDir);
        }
    }

    @Test
    @DisplayName("锁引用计数归零后会从静态 Map 移除，避免随应用数无限增长")
    @SuppressWarnings("unchecked")
    void lockRefShouldBeRemovedWhenUnused() {
        Long appId = 123456788L;
        Map<Long, AppServiceImpl.LockRef> locks =
                (Map<Long, AppServiceImpl.LockRef>) ReflectionTestUtils.getField(AppServiceImpl.class, "DEPLOY_LOCKS");

        AppServiceImpl.LockRef ref = AppServiceImpl.acquireDeployLock(appId);
        assertTrue(locks.containsKey(appId), "使用中的锁必须留在 Map 里，否则会变成两把锁");

        AppServiceImpl.releaseDeployLock(appId, ref);
        assertFalse(locks.containsKey(appId), "没有使用者后必须移除，避免静态 Map 无限增长");
    }
}
