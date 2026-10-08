package com.example.aicodebackend.constant;

public interface AppConstant {

    /**
     * 应用名称最大长度（创建应用时取初始化提示词的前 N 位作为应用名称）
     */
    int APP_NAME_MAX_LENGTH = 20;

    /**
     * 精选应用的优先级
     */
    Integer GOOD_APP_PRIORITY = 99;

    /**
     * 默认应用优先级
     */
    Integer DEFAULT_APP_PRIORITY = 0;

    /**
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/temp/code_output";

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = System.getProperty("user.dir") + "/temp/code_deploy";
}
