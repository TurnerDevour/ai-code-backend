package com.example.aicodebackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "cos.client")
public class CosClientConfig {
    /**
     * COS 域名
     */
    private String host;

    /**
     * COS 所在区域
     */
    private String region;

    /**
     * COS 存储桶
     */
    private String bucket;

    /**
     * COS 密钥 ID
     */
    private String secretId;

    /**
     * COS 密钥
     */
    private String secretKey;

    @Bean
    public COSClient cosClient() {
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        return new COSClient(new BasicCOSCredentials(secretId, secretKey), clientConfig);
    }

}
