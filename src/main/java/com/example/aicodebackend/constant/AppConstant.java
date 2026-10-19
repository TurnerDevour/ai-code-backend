package com.example.aicodebackend.constant;

public interface AppConstant {

    int APP_NAME_MAX_LENGTH = 20;

    Integer GOOD_APP_PRIORITY = 99;

    Integer DEFAULT_APP_PRIORITY = 0;

    /** 临时文件根目录（与 user.dir 绑定） */
    String TEMP_ROOT_DIR = System.getProperty("user.dir") + "/temp";

    /** 应用生成目录 */
    String CODE_OUTPUT_ROOT_DIR = TEMP_ROOT_DIR + "/code_output";

    /** 应用部署目录 */
    String CODE_DEPLOY_ROOT_DIR = TEMP_ROOT_DIR + "/code_deploy";

    /** 截图临时目录名 */
    String SCREENSHOT_DIR_NAME = "screenshots";
}
