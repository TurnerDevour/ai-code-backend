package com.example.aicodebackend;

import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiCodeBackendApplicationTests {

    @Test
    void testMd5() {
        String password = "12345678";
        String salt = "ai_code";
        String encryptedPassword = DigestUtil.md5Hex(salt + password);
        System.out.println("加密后的密码：" + encryptedPassword);
    }

}
