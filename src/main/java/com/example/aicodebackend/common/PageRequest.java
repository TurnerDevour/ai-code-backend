package com.example.aicodebackend.common;

import lombok.Data;

@Data
public class PageRequest {

    /** 默认排序字段 */
    public static final String DEFAULT_SORT_FIELD = "create_time";

    /** 升序标记 */
    public static final String ASCEND = "ascend";

    /** 降序标记 */
    public static final String DESCEND = "descend";

    /** 当前页数 */
    private long pageNum = 1;

    /** 每页条数 */
    private long pageSize = 10;

    /** 排序字段 */
    private String sortField;

    /** 排序顺序（默认降序） */
    private String sortOrder = DESCEND;

    /** 是否升序 */
    public boolean isAscending() {
        return ASCEND.equals(sortOrder);
    }
}
