package com.example.aicodebackend.service.impl;

import cn.hutool.core.io.FileUtil;
import com.example.aicodebackend.constant.AppConstant;
import com.example.aicodebackend.core.builder.DeployQueueManager;
import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.mapper.AppMapper;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.CodeGenTypeEnum;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.mybatisflex.core.query.QueryCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同步部署的状态机流程
 * 覆盖 P3 最关键的两条路径……
 */
class AppServiceImplSyncDeployFlowTest {

    @Test
    @DisplayName("同步部署成功：先抢占 deploying（带 taskId），构建返回部署地址")
    void syncDeployShouldClaimDeployingAndReturnUrl() throws Exception {
        Long appId = 876543210L;
        StubAppService service = new StubAppService(appId, false);
        File sourceDir = service.createSourceDir(appId);

        try {
            String url = service.deployApp(appId, service.user());

            assertEquals("https://wlbc.top/dist/sync123/", url);
            assertNotNull(service.capturing.entity, "必须先落状态再构建");
            assertEquals(DeployStatusEnum.DEPLOYING.getValue(), service.capturing.entity.getDeployStatus());
            assertNotNull(service.capturing.entity.getDeployTaskId(), "同步部署也要写 taskId，旧 MQ 消息才不会误抢");
            assertEquals(7L, service.capturing.entity.getDeployOperatorId());
        } finally {
            FileUtil.del(sourceDir);
        }
    }

    @Test
    @DisplayName("同步部署失败：状态落 failed（并清空 taskId），异常原样抛给调用方")
    void syncDeployFailureShouldMarkFailed() {
        Long appId = 876543211L;
        StubAppService service = new StubAppService(appId, true);
        File sourceDir = service.createSourceDir(appId);

        try {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.deployApp(appId, service.user()));
            assertTrue(error.getMessage().contains("构建失败"));

            assertNotNull(service.capturing.entity);
            assertEquals(DeployStatusEnum.FAILED.getValue(), service.capturing.entity.getDeployStatus());
            assertEquals("构建失败", service.capturing.entity.getDeployError());
            assertEquals("", service.capturing.entity.getDeployTaskId(), "失败后必须清 token，防止迟到消息重跑");
        } finally {
            FileUtil.del(sourceDir);
        }
    }

    /** 只替换"读应用"和"执行构建"，其余状态 CAS 走真实逻辑 + 假 Mapper */
    private static final class StubAppService extends AppServiceImpl {

        private final App app;

        private final boolean fail;

        private final CapturingMapper capturing = new CapturingMapper();

        StubAppService(Long appId, boolean fail) {
            this.app = new App();
            this.app.setId(appId);
            this.app.setUserId(7L);
            this.app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
            this.app.setDeployStatus(DeployStatusEnum.IDLE.getValue());
            this.fail = fail;

            DeployQueueManager manager = mock(DeployQueueManager.class);
            try {
                when(manager.runWithPermit(ArgumentMatchers.<Callable<String>>any())).thenAnswer(invocation -> {
                    Callable<?> action = invocation.getArgument(0);
                    return action.call();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            ReflectionTestUtils.setField(this, "deployQueueManager", manager);
            ReflectionTestUtils.setField(this, "deployHeartbeat",
                    mock(com.example.aicodebackend.core.builder.DeployHeartbeat.class));
            AppMapper mapperProxy = (AppMapper) Proxy.newProxyInstance(
                    AppMapper.class.getClassLoader(),
                    new Class<?>[]{AppMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "updateByCondition" ->
                                capturing.updateByCondition((App) args[0], (QueryCondition) args[1]);
                        case "toString" -> "SyncFlowAppMapper";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            ReflectionTestUtils.setField(this, "mapper", mapperProxy);
        }

        @Override
        public App getById(Serializable id) {
            return app;
        }

        @Override
        protected String doDeploy(App app, Long appId, String codeGenType, File sourceDir) {
            if (fail) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "构建失败");
            }
            app.setDeployStatus(DeployStatusEnum.READY.getValue());
            return "https://wlbc.top/dist/sync123/";
        }

        File createSourceDir(Long appId) {
            File dir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + app.getCodeGenType() + "_" + appId);
            try {
                Files.createDirectories(dir.toPath());
            } catch (Exception e) {
                throw new IllegalStateException("创建测试目录失败", e);
            }
            return dir;
        }

        User user() {
            User user = new User();
            user.setId(7L);
            return user;
        }
    }

    /** 只实现 updateByCondition：记录最后一次写的实体（claim / failed） */
    private static final class CapturingMapper {

        private App entity;

        int updateByCondition(App entity, QueryCondition condition) {
            this.entity = entity;
            return 1;
        }
    }
}
