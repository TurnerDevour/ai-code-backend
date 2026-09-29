package com.example.aicodebackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Objects;

@SpringBootApplication
@MapperScan("com.example.aicodebackend.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class AiCodeBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeBackendApplication.class, args);
    }

}
