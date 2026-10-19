package com.example.aicodebackend.common;

import com.example.aicodebackend.exception.ErrorCode;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@SuppressWarnings("serial")
public class BaseResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int code;

    private T data;

    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}

