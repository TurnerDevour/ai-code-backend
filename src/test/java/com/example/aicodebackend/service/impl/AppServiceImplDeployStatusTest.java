package com.example.aicodebackend.service.impl;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.model.entity.App;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.model.enums.DeployStatusEnum;
import com.example.aicodebackend.model.vo.DeployStatusVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步部署状态机的回归测试
 * <p>
 * 关注三件事：
 * 1. 状态视图的推导（含"改造前历史数据没有 deploy_status"的兼容分支）；
 * 2. 部署操作权限（创建者 或 部署发起人）；
 * 3. 失败原因要面向用户、长度受限、不泄露堆栈。
 */
class AppServiceImplDeployStatusTest {

    private final AppServiceImpl appService = new AppServiceImpl();

    @Test
    @DisplayName("历史数据（无 deploy_status）有 deployKey 时按部署完成返回")
    void legacyRowWithDeployKeyShouldBeReady() {
        App app = new App();
        app.setId(1L);
        app.setDeployKey("abc123");
        app.setDeployedTime(LocalDateTime.of(2026, 10, 6, 16, 0));

        DeployStatusVO vo = invokeBuildDeployStatus(app);

        assertEquals(DeployStatusEnum.READY.getValue(), vo.getStatus());
        assertEquals("http://localhost/abc123/", vo.getDeployUrl());
        assertEquals(LocalDateTime.of(2026, 10, 6, 16, 0), vo.getDeployedTime());
    }

    @Test
    @DisplayName("历史数据（无 deploy_status）无 deployKey 时按空闲返回")
    void legacyRowWithoutDeployKeyShouldBeIdle() {
        App app = new App();
        app.setId(2L);

        DeployStatusVO vo = invokeBuildDeployStatus(app);

        assertEquals(DeployStatusEnum.IDLE.getValue(), vo.getStatus());
        assertNull(vo.getDeployUrl());
    }

    @Test
    @DisplayName("deploying 状态返回部署中，且不返回旧的部署地址")
    void deployingStatusShouldNotLeakStaleUrl() {
        App app = new App();
        app.setId(3L);
        app.setDeployStatus(DeployStatusEnum.DEPLOYING.getValue());
        app.setDeployKey("oldkey");

        DeployStatusVO vo = invokeBuildDeployStatus(app);

        assertEquals(DeployStatusEnum.DEPLOYING.getValue(), vo.getStatus());
        assertNull(vo.getDeployUrl(), "部署中不应把上一次的地址返回给前端");
    }

    @Test
    @DisplayName("failed 状态返回失败原因")
    void failedStatusShouldCarryErrorMessage() {
        App app = new App();
        app.setId(4L);
        app.setDeployStatus(DeployStatusEnum.FAILED.getValue());
        app.setDeployError("npm install 执行失败");

        DeployStatusVO vo = invokeBuildDeployStatus(app);

        assertEquals(DeployStatusEnum.FAILED.getValue(), vo.getStatus());
        assertEquals("npm install 执行失败", vo.getErrorMessage());
    }

    @Test
    @DisplayName("部署权限：创建者与部署发起人都可以查看状态")
    void deployOperatorShouldBeOwnerOrSubmitter() {
        App app = new App();
        app.setUserId(100L);
        app.setDeployOperatorId(200L);

        assertTrue(invokeIsDeployOperator(app, user(100L)), "应用创建者应有权查看部署状态");
        assertTrue(invokeIsDeployOperator(app, user(200L)), "异步部署发起人应有权查看部署状态");
        assertFalse(invokeIsDeployOperator(app, user(300L)), "无关用户不应有权查看部署状态");
        assertFalse(invokeIsDeployOperator(null, user(100L)), "应用不存在时应判为无权限");
        assertFalse(invokeIsDeployOperator(app, null), "未登录时应判为无权限");
    }

    @Test
    @DisplayName("失败原因：业务异常取其 message，未知异常不泄露堆栈，超长截断")
    void deployErrorMessageShouldBeUserFriendly() {
        String business = invokeResolveDeployErrorMessage(new BusinessException(50000, "Vue 项目构建失败，请重试"));
        assertEquals("Vue 项目构建失败，请重试", business);

        String unknown = invokeResolveDeployErrorMessage(new IllegalStateException("jdbc:mysql://localhost:3306/ai_code_db connect failed"));
        assertFalse(unknown.contains("Exception"), "不应把异常类名暴露给用户: " + unknown);
        assertTrue(unknown.length() <= 500, "失败原因长度应受限");

        String blank = invokeResolveDeployErrorMessage(new RuntimeException());
        assertFalse(blank.isBlank(), "空消息时应给出兜底文案");
    }

    @Test
    @DisplayName("可提交部署的判定：idle/failed/历史空状态可提交；已部署但代码改过可重新部署；部署中不可提交")
    void submittableJudgementShouldCoverAllStates() {
        // 从未部署（历史数据）且没有 deployKey -> 可提交
        App legacyNoKey = new App();
        assertTrue(invokeIsDeploySubmittable(legacyNoKey), "历史数据（无状态、无 deployKey）应可提交");

        // 历史数据但已有 deployKey -> 视为已部署，不可提交
        App legacyWithKey = new App();
        legacyWithKey.setDeployKey("abc123");
        assertFalse(invokeIsDeploySubmittable(legacyWithKey), "已有部署地址的历史数据不应被重复提交");

        App idle = new App();
        idle.setDeployStatus(DeployStatusEnum.IDLE.getValue());
        assertTrue(invokeIsDeploySubmittable(idle), "空闲状态应可提交");

        App failed = new App();
        failed.setDeployStatus(DeployStatusEnum.FAILED.getValue());
        assertTrue(invokeIsDeploySubmittable(failed), "失败后应允许重新提交");

        // 已部署且代码没有改过 -> 不重复提交（避免无意义的重新构建）
        App ready = new App();
        ready.setDeployStatus(DeployStatusEnum.READY.getValue());
        ready.setDeployedTime(LocalDateTime.now().minusMinutes(10));
        ready.setEditTime(LocalDateTime.now().minusMinutes(30));
        assertFalse(invokeIsDeploySubmittable(ready), "已部署且代码未改动时不应重复提交");

        // 已部署但改过代码 -> 允许重新部署（否则用户改完应用只能一直看旧站点）
        App readyWithNewCode = new App();
        readyWithNewCode.setDeployStatus(DeployStatusEnum.READY.getValue());
        readyWithNewCode.setDeployedTime(LocalDateTime.now().minusMinutes(30));
        readyWithNewCode.setEditTime(LocalDateTime.now().minusMinutes(1));
        assertTrue(invokeIsDeploySubmittable(readyWithNewCode), "代码改过之后应允许重新部署");

        App freshDeploying = new App();
        freshDeploying.setDeployStatus(DeployStatusEnum.DEPLOYING.getValue());
        freshDeploying.setUpdateTime(LocalDateTime.now());
        assertFalse(invokeIsDeploySubmittable(freshDeploying), "正在部署中的应用不应被重复提交");

        App staleDeploying = new App();
        staleDeploying.setDeployStatus(DeployStatusEnum.DEPLOYING.getValue());
        staleDeploying.setUpdateTime(LocalDateTime.now().minusMinutes(31));
        assertTrue(invokeIsDeploySubmittable(staleDeploying), "僵死的部署中状态应允许重新提交");

        assertFalse(invokeIsDeploySubmittable(null), "应用为空时应判为不可提交");
    }

    @Test
    @DisplayName("部署状态视图：代码改过之后 deployStale=true 并提示可重新部署")
    void deployStatusShouldExposeStaleFlag() {
        App ready = new App();
        ready.setId(1L);
        ready.setDeployStatus(DeployStatusEnum.READY.getValue());
        ready.setDeployKey("abc123");
        ready.setDeployedTime(LocalDateTime.now().minusMinutes(30));
        ready.setEditTime(LocalDateTime.now().minusMinutes(1));

        DeployStatusVO staleVo = invokeBuildDeployStatus(ready);
        assertEquals(DeployStatusEnum.READY.getValue(), staleVo.getStatus());
        assertEquals(Boolean.TRUE, staleVo.getDeployStale(), "代码晚于部署时间时应标记为需要重新部署");
        assertTrue(staleVo.getMessage().contains("重新部署"), "应提示用户可重新部署，实际: " + staleVo.getMessage());

        ready.setEditTime(LocalDateTime.now().minusMinutes(60));
        DeployStatusVO freshVo = invokeBuildDeployStatus(ready);
        assertEquals(Boolean.FALSE, freshVo.getDeployStale(), "代码早于部署时间时不应标记为需要重新部署");
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private DeployStatusVO invokeBuildDeployStatus(App app) {
        return (DeployStatusVO) ReflectionTestUtils.invokeMethod(appService, "buildDeployStatus", app);
    }

    private boolean invokeIsDeployOperator(App app, User user) {
        Boolean result = (Boolean) ReflectionTestUtils.invokeMethod(appService, "isDeployOperator", app, user);
        return Boolean.TRUE.equals(result);
    }

    private boolean invokeIsDeploySubmittable(App app) {
        Boolean result = (Boolean) ReflectionTestUtils.invokeMethod(appService, "isDeploySubmittable", app);
        return Boolean.TRUE.equals(result);
    }

    private String invokeResolveDeployErrorMessage(Throwable e) {
        return (String) ReflectionTestUtils.invokeMethod(appService, "resolveDeployErrorMessage", e);
    }
}
