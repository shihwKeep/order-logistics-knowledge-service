package com.xjjk.knowledge.document.storage;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class DocumentStorageConfiguration {

    @Bean
    public UploadPolicy uploadPolicy(DocumentStorageProperties properties) {
        return new UploadPolicy(properties.getMaxFileSize().toBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "knowledge.document.storage", name = "enabled", havingValue = "true")
    public MinioClient minioClient(DocumentStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
