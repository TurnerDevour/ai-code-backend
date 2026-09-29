package com.example.aicodebackend;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
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
