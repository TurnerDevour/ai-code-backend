package com.example.aicodebackend.controller;

import com.example.aicodebackend.exception.BusinessException;
import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.model.entity.User;
import com.example.aicodebackend.service.AppService;
import com.example.aicodebackend.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式生成接口的错误兜底回归测试
 * <p>
 * 接口契约：该接口始终以 SSE 形式响应，任何失败都转成 type=error 数据帧 + done 事件，
 * 既不抛出异常给 Servlet 层，也不依赖客户端的 Accept 头。
 * <p>
 * 说明：这里用 JDK 动态代理手写桩对象，而不是 Mockito —— 当前运行环境下 byte-buddy
 * 无法初始化 MockMaker，Mockito 会直接报错。
 */
class AppControllerSseStreamErrorTest {

    @Test
    @DisplayName("正常结束：只下发业务数据与 done 事件")
    void normalCompletionShouldEmitDataAndDone() {
        List<ServerSentEvent<String>> events = invoke(
                newController(stubAppService(Flux.just("{\"type\":\"ai_response\",\"data\":\"hello\"}"))),
                1L, "生成一个博客首页");

        assertNotNull(events, "流式响应事件列表不应为空");
        assertEquals(2, events.size(), "应下发业务数据与 done 事件各一个");
        assertTrue(events.get(0).data().contains("ai_response"), "第一个事件应为业务数据帧");
        assertEquals("done", events.get(1).event(), "最后一个事件应为 done");
    }

    @Test
    @DisplayName("流内异常：先下发 error 数据帧，再下发 done 事件")
    void streamErrorShouldBeConvertedToErrorFrame() {
        List<ServerSentEvent<String>> events = invoke(
                newController(stubAppService(Flux.error(new IllegalStateException("模型调用失败")))),
                1L, "生成一个博客首页");

        assertNotNull(events, "流式响应事件列表不应为空");
        assertEquals(2, events.size(), "应下发 error 数据帧与 done 事件各一个");
        assertTrue(events.get(0).data().contains("\"type\":\"error\""), "第一个事件应为 type=error 的数据帧");
        assertEquals("done", events.get(1).event(), "最后一个事件应为 done");
    }

    @Test
    @DisplayName("参数校验失败：不依赖 Accept 头，直接以 error 数据帧 + done 结束")
    void invalidParamsShouldBecomeErrorFrame() {
        // 请求不设置任何 Accept 头，验证该行为与客户端可接受的媒体类型无关
        List<ServerSentEvent<String>> events = invoke(
                newController(stubAppService(Flux.empty())),
                0L, "生成一个博客首页");

        assertNotNull(events, "流式响应事件列表不应为空");
        assertEquals(2, events.size(), "应下发 error 数据帧与 done 事件各一个");
        assertTrue(events.get(0).data().contains("\"type\":\"error\""), "第一个事件应为 type=error 的数据帧");
        assertTrue(events.get(0).data().contains("应用ID不合法"), "应下发具体的参数校验提示");
        assertEquals("done", events.get(1).event(), "最后一个事件应为 done");
    }

    @Test
    @DisplayName("鉴权/业务异常：以 error 数据帧下发业务提示")
    void businessExceptionShouldBecomeErrorFrame() {
        List<ServerSentEvent<String>> events = invoke(
                newController(throwingAppService(new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用"))),
                1L, "生成一个博客首页");

        assertEquals(2, events.size(), "应下发 error 数据帧与 done 事件各一个");
        assertTrue(events.get(0).data().contains("无权限访问该应用"), "应下发业务异常自身的提示");
        assertEquals("done", events.get(1).event(), "最后一个事件应为 done");
    }

    @Test
    @DisplayName("未知运行时异常：以通用文案下发，不泄露内部细节")
    void unexpectedExceptionShouldBecomeGenericErrorFrame() {
        List<ServerSentEvent<String>> events = invoke(
                newController(throwingAppService(new IllegalStateException("connect jdbc:mysql://localhost:3306/ai_code_db failed"))),
                1L, "生成一个博客首页");

        assertEquals(2, events.size(), "应下发 error 数据帧与 done 事件各一个");
        String errorFrameData = events.get(0).data();
        assertTrue(errorFrameData.contains("代码生成失败，请稍后重试"), "应下发通用错误提示");
        assertFalse(errorFrameData.contains("jdbc:mysql"), "不应把内部异常细节下发给前端");
        assertEquals("done", events.get(1).event(), "最后一个事件应为 done");
    }

    /**
     * 以桩对象构造 AppController 并消费其流式响应
     *
     * @param appController 已注入桩服务的控制器
     * @param appId         应用ID
     * @param prompt        提示词
     *
     * @return 控制器实际下发的事件列表
     */
    private List<ServerSentEvent<String>> invoke(AppController appController, Long appId, String prompt) {
        return appController
                .chatToGenCode(appId, prompt, new MockHttpServletRequest())
                .collectList()
                .block();
    }

    private AppController newController(AppService appService) {
        AppController appController = new AppController();
        ReflectionTestUtils.setField(appController, "appService", appService);
        ReflectionTestUtils.setField(appController, "userService", stubUserService());
        return appController;
    }

    private AppService stubAppService(Flux<String> codeStream) {
        return stubAppService(codeStream, null);
    }

    private AppService throwingAppService(RuntimeException exception) {
        return stubAppService(null, exception);
    }

    /**
     * 手写 AppService 桩对象：chatToGenCode 返回指定流，或直接抛出指定异常
     */
    private AppService stubAppService(Flux<String> codeStream, RuntimeException exception) {
        return (AppService) Proxy.newProxyInstance(
                AppService.class.getClassLoader(),
                new Class<?>[]{AppService.class},
                (proxy, method, args) -> {
                    if ("chatToGenCode".equals(method.getName())) {
                        if (exception != null) {
                            throw exception;
                        }
                        return codeStream;
                    }
                    return switch (method.getName()) {
                        case "toString" -> "StubAppService";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
    }

    private UserService stubUserService() {
        return (UserService) Proxy.newProxyInstance(
                UserService.class.getClassLoader(),
                new Class<?>[]{UserService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLoginUser" -> new User();
                    case "toString" -> "StubUserService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
