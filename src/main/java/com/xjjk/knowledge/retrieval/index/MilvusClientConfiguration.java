package com.xjjk.knowledge.retrieval.index;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅在显式启用 Milvus 时创建 SDK 客户端，单元测试和纯管理启动不触网。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "knowledge.retrieval.milvus", name = "enabled", havingValue = "true")
public class MilvusClientConfiguration {
    @Bean(destroyMethod = "close")
    MilvusClientV2 knowledgeMilvusClient(MilvusProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(properties.getUri())
                .dbName(properties.getDatabaseName())
                .connectTimeoutMs(properties.getConnectTimeout().toMillis())
                .rpcDeadlineMs(properties.getRequestTimeout().toMillis())
                .enablePrecheck(true);
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.token(properties.getToken());
        }
        return new MilvusClientV2(builder.build());
    }
}
