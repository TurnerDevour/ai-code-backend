package com.example.aicodebackend.common;

import com.example.aicodebackend.exception.ErrorCode;
import com.example.aicodebackend.exception.ThrowUtils;
import com.mybatisflex.core.paginate.Page;

import java.util.List;
import java.util.function.Function;

/** 分页参数校验与实体页 -> VO 页转换 */
public final class PageUtils {

    public static final long DEFAULT_MAX_PAGE_SIZE = 20;

    private PageUtils() {
    }

    /** 校验页码与每页条数 */
    public static void validate(long pageNum, long pageSize) {
        ThrowUtils.throwIf(pageNum <= 0, ErrorCode.PARAMS_ERROR, "页码不合法");
        ThrowUtils.throwIf(pageSize <= 0, ErrorCode.PARAMS_ERROR, "每页条数不合法");
    }

    /** 校验页码与每页条数（带上限） */
    public static void validate(long pageNum, long pageSize, long maxPageSize, String message) {
        validate(pageNum, pageSize);
        ThrowUtils.throwIf(pageSize > maxPageSize, ErrorCode.PARAMS_ERROR, message);
    }

    /** 保持分页元信息，只把记录转换成 VO */
    public static <E, V> Page<V> toVoPage(Page<E> source, long pageNum, long pageSize,
                                          Function<List<E>, List<V>> converter) {
        Page<V> target = new Page<>(pageNum, pageSize, source.getTotalRow());
        target.setRecords(converter.apply(source.getRecords()));
        return target;
    }
}
