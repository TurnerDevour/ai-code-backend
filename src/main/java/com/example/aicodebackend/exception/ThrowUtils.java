package com.example.aicodebackend.exception;

public class ThrowUtils {

    /** 条件成立则抛异常 */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /** 条件成立则抛异常 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /** 条件成立则抛异常 */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }

    /** ID 为空或非正数时抛出参数错误 */
    public static void throwIfInvalidId(Long id, String name) {
        throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, name + "不合法");
    }
}
