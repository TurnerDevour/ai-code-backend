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

    /**
     * 处理检验参数异常（MethodArgumentNotValidException）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("MethodArgumentNotValidException", e);
        String errorMessage = Objects.requireNonNull(e.getBindingResult().getFieldError()).getDefaultMessage();
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, errorMessage);
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        log.error("BusinessException", e);
        if (markSseErrorAsHandled(request, response, e.getCode(), e.getMessage())) {
            // 已按 SSE 数据帧下发错误，返回 null 让 Spring 跳过 JSON 回写
            return null;
        }
        return ResultUtils.error(e.getCode(), e.getMessage());
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
     * <p>
     * 流式接口的响应可能已经按 text/event-stream 协商（甚至已经开始下发数据），此时没有任何 JSON 转换器可用：
     * 继续返回 BaseResponse 会抛出 HttpMessageNotWritableException，而该异常又会被本类的 RuntimeException
     * 处理器再次捕获、再次失败，最终由 Tomcat 渲染错误页，形成连锁异常且前端拿不到任何错误信息。
     * <p>
     * 因此这里统一返回 null（让 Spring 跳过 JSON 回写），并按响应状态分两种情况处理：
     * <ul>
     *     <li>响应未提交：直接写入一个 type=error 的 SSE 数据帧，前端按消息类型统一展示错误；</li>
     *     <li>响应已提交（流已开始下发）：客户端连接即将结束，只记录日志，不再尝试写入任何内容。</li>
     * </ul>
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param code     错误码，仅用于日志
     * @param message  错误提示，为空时回落到「系统错误」
     *
     * @return true 表示当前请求是 SSE 请求、已按 SSE 语义处理完毕（调用方应返回 null）
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
     * <p>
     * 注意：Spring 进入异常处理前会移除原始请求的 produces 属性（PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE）
     * 并清空响应的 Content-Type（见 DispatcherServlet#processHandlerException），
     * 因此不能依赖这两者来判断，只能以客户端可接受的媒体类型为准：
     * 只有当客户端不接受 JSON（例如 EventSource 只发 Accept: text/event-stream）时，才按 SSE 收口。
     * <p>
     * 若响应上仍留有 text/event-stream（响应已提交、Content-Type 未被清掉的情况），同样视为 SSE。
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
