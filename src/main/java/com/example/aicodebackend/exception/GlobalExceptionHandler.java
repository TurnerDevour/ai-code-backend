package com.example.aicodebackend.exception;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.example.aicodebackend.ai.model.message.ErrorMessage;
import com.example.aicodebackend.common.BaseResponse;
import com.example.aicodebackend.common.ResultUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 处理检验参数异常（MethodArgumentNotValidException） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("MethodArgumentNotValidException", e);
        String errorMessage = Objects.requireNonNull(e.getBindingResult().getFieldError()).getDefaultMessage();
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, errorMessage);
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        // 限流/排队已满、状态机不允许本次操作都属于预期内的拒绝，不是系统故障：
        // 只打一行 warn，避免突发流量或用户重复点击刷出大量堆栈把真故障淹没
        if (isExpectedRejection(e)) {
            log.warn("BusinessException(expected): {}", e.getMessage());
        } else {
            log.error("BusinessException", e);
        }
        if (markSseErrorAsHandled(request, response, e.getCode(), e.getMessage())) {
            // 已按 SSE 数据帧下发错误，返回 null 让 Spring 跳过 JSON 回写
            return null;
        }
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /** 是否为预期内的业务拒绝（背压 / 状态机不允许） */
    private boolean isExpectedRejection(BusinessException e) {
        int code = e.getCode();
        return code == ErrorCode.TOO_MANY_REQUESTS.getCode()
                || code == ErrorCode.OPERATION_ERROR.getCode();
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e, HttpServletRequest request, HttpServletResponse response) {
        log.error("RuntimeException", e);
        if (markSseErrorAsHandled(request, response, ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage())) {
            return null;
        }
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage());
    }

    /**
     * SSE（text/event-stream）接口的错误收口
     * 流式接口的响应可能已经按 text/event-stream 协商（甚至已经开始下发数据），此时没有任何 JSON 转换器可用：
     * 继续返回 BaseResponse 会抛出 HttpMessageNotWritableException，而该异常又会被本类的 RuntimeException……
     */
    private boolean markSseErrorAsHandled(HttpServletRequest request, HttpServletResponse response, int code, String message) {
        if (!isSseRequest(request, response)) {
            return false;
        }
        String errorMessage = message == null ? "系统错误" : message;
        if (response.isCommitted()) {
            // 响应已提交：内容已经部分下发，无法再补发错误帧。
            // 这里只记录日志并结束当前请求，不再让异常继续向外传播（继续传播会触发二次异常处理与 Tomcat 错误页）。
            log.error("SSE 响应已提交，无法回写错误数据帧, code: {}, message: {}", code, errorMessage);
            return true;
        }
        try {
            // 只清空缓冲区、不调用 reset()：reset() 会连带清掉 CORS 等响应头
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("data:" + JSONUtil.toJsonStr(new ErrorMessage(errorMessage)) + "\n\n");
            response.getWriter().flush();
        } catch (Exception ex) {
            log.error("回写 SSE 错误数据帧失败, code: {}, message: {}", code, errorMessage, ex);
        }
        return true;
    }

    /**
     * 判断当前请求是否走 SSE
     * 注意：Spring 进入异常处理前会移除原始请求的 produces 属性（PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE）
     * 并清空响应的 Content-Type（见 DispatcherServlet#processHandlerException）……
     */
    private boolean isSseRequest(HttpServletRequest request, HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String acceptHeader = request.getHeader(HttpHeaders.ACCEPT);
        if (StrUtil.isBlank(acceptHeader)) {
            return false;
        }
        List<MediaType> acceptedTypes;
        try {
            acceptedTypes = MediaType.parseMediaTypes(acceptHeader);
        } catch (Exception ex) {
            // Accept 头非法时交给 Spring 自身的媒体类型协商逻辑处理
            return false;
        }
        boolean acceptsJson = acceptedTypes.stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.APPLICATION_JSON));
        boolean acceptsSse = acceptedTypes.stream()
                .anyMatch(MediaType.TEXT_EVENT_STREAM::includes);
        return acceptsSse && !acceptsJson;
    }
}
