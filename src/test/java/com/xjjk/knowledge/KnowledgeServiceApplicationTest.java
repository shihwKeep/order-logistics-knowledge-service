package com.xjjk.knowledge;

import com.xjjk.knowledge.audit.AuditMapper;
import com.xjjk.knowledge.audit.AuditQueryMapper;
import com.xjjk.knowledge.document.persistence.DocumentMapper;
import com.xjjk.knowledge.document.service.DocumentManagementMapper;
import com.xjjk.knowledge.document.task.IngestionArtifactMapper;
import com.xjjk.knowledge.document.task.IngestionTaskMapper;
import com.xjjk.knowledge.knowledgebase.persistence.KnowledgeBaseMapper;
import com.xjjk.knowledge.retrieval.indexing.ChunkIndexMapper;
import com.xjjk.knowledge.retrieval.indexing.IndexRecoveryMapper;
import com.xjjk.knowledge.retrieval.service.PublishedVersionMapper;
import com.xjjk.knowledge.retrieval.service.SearchLogMapper;
import com.xjjk.knowledge.publication.PublicationMapper;
import com.xjjk.knowledge.retrieval.service.DraftVersionMapper;
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
    private AuditQueryMapper auditQueryMapper;

    @MockitoBean
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    private DocumentMapper documentMapper;

    @MockitoBean
    private DocumentManagementMapper documentManagementMapper;

    @MockitoBean
    private IngestionArtifactMapper ingestionArtifactMapper;

    @MockitoBean
    private IngestionTaskMapper ingestionTaskMapper;

    @MockitoBean
    private ChunkIndexMapper chunkIndexMapper;

    @MockitoBean
    private IndexRecoveryMapper indexRecoveryMapper;

    @MockitoBean
    private PublishedVersionMapper publishedVersionMapper;

    @MockitoBean
    private SearchLogMapper searchLogMapper;

    @MockitoBean
    private PublicationMapper publicationMapper;

    @MockitoBean
    private DraftVersionMapper draftVersionMapper;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void contextLoads() {
    }
}
