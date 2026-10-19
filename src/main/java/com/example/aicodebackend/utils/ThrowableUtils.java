package com.example.aicodebackend.utils;

import java.util.function.Predicate;

/** 异常链查找工具 */
public final class ThrowableUtils {

    private ThrowableUtils() {
    }

    /** 在异常链中查找指定类型的异常 */
    public static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /** 异常链中是否存在某个异常的消息同时包含全部片段 */
    public static boolean anyMessageContains(Throwable error, String... fragments) {
        return anyCauseMatches(error, cause -> {
            String message = cause.getMessage();
            if (message == null) {
                return false;
            }
            for (String fragment : fragments) {
                if (!message.contains(fragment)) {
                    return false;
                }
            }
            return true;
        });
    }

    /** 异常链中是否存在满足条件的异常 */
    public static boolean anyCauseMatches(Throwable error, Predicate<Throwable> predicate) {
        Throwable current = error;
        while (current != null) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
