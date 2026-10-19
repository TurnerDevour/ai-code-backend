package com.example.aicodebackend.model.enums;

import lombok.Getter;

/** 用户默认值与密码盐值 */
@Getter
public enum UserDefaultEnum {

    PASSWORD("123456"),
    AVATAR("https://zos.alipayobjects.com/rmsportal/ODTLcjxAfvqbxHnVXCYX.png"),
    PROFILE("这是一个用户简介。"),
    PASSWORD_SALT("ai_code");

    private final String value;

    UserDefaultEnum(String value) {
        this.value = value;
    }
}
