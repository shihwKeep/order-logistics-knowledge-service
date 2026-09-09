package com.xjjk.knowledge;

import com.xjjk.knowledge.audit.AuditMapper;
import com.xjjk.knowledge.knowledgebase.persistence.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "knowledge.sspx.base-url=http://localhost:9092",
        "knowledge.sspx.client-id=test-client",
        "knowledge.sspx.client-secret=test-secret",
        "knowledge.sspx.application-id=444",
        "knowledge.admin-session.cookie-name=KB_ADMIN_SESSION",
        "knowledge.admin-session.idle-timeout=30m",
        "knowledge.admin-session.absolute-timeout=8h",
        "knowledge.admin-session.role-cache-ttl=5m",
        "knowledge.admin-session.touch-interval=1m",
        "knowledge.admin-session.secure-cookie=false"
})
class KnowledgeServiceApplicationTest {

    @MockitoBean
    private AuditMapper auditMapper;

    @MockitoBean
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void contextLoads() {
    }
}
