package com.example.aicodebackend.exception;

import com.example.aicodebackend.common.BaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 接口异常收口回归测试
 * <p>
 * 流式接口（text/event-stream）出错时不能再以 JSON 结构回写错误体，
 * 否则会抛出 HttpMessageNotWritableException，并触发异常处理器的二次调用与 Tomcat 错误页渲染。
 */
class GlobalExceptionHandlerSseTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("SSE 接口出错：下发 type=error 数据帧，而不是 JSON 错误体")
    void sseEndpointShouldReturnErrorDataFrame() throws Exception {
        mockMvc.perform(get("/stub/sse/error").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("data:")))
                .andExpect(content().string(containsString("\"type\":\"error\"")))
                .andExpect(content().string(containsString("应用ID不合法")));
    }

    @Test
    @DisplayName("普通接口出错：仍然返回 JSON 结构的 BaseResponse")
    void normalEndpointShouldStillReturnJsonError() throws Exception {
        mockMvc.perform(get("/stub/json/error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("\"code\":" + ErrorCode.PARAMS_ERROR.getCode())))
                .andExpect(content().string(containsString("应用ID不合法")));
    }

    @RestController
    static class StubController {

        @GetMapping(value = "/stub/sse/error", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<ServerSentEvent<String>> sseError() {
            // 模拟流式接口在返回 Flux 之前就失败（参数校验、鉴权等）
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID不合法");
        }

        @GetMapping("/stub/json/error")
        public BaseResponse<?> jsonError() {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID不合法");
        }
    }
}
