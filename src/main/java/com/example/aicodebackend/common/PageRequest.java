package com.example.aicodebackend.common;

import lombok.Data;

@Data
public class PageRequest {

    /**
     * 当前页数
     */
    private long current = 1;

    /**
     * 每页条数
     */
    private long pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序顺序（默认降序）
     */
    private String sortOrder = "descend";
}
