package com.xjjk.knowledge.retrieval.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class DerivedIndexResetService {
    public static final String CONFIRMATION = "RESET_BAILIAN_V2_DERIVED_INDEXES";
    private static final Logger log = LoggerFactory.getLogger(DerivedIndexResetService.class);
    private static final List<String> ES_INDEXES = List.of(
            "knowledge_chunks_draft_v1", "knowledge_chunks_published_v1",
            "knowledge_chunks_draft_v2", "knowledge_chunks_published_v2",
            "agent-user-memory-v1", "agent-user-memory-v2");
    private static final List<String> MILVUS_COLLECTIONS = List.of(
            "knowledge_chunks_draft_v1", "knowledge_chunks_published_v1",
            "knowledge_chunks_draft_v2", "knowledge_chunks_published_v2",
            "agent_user_memory_v1", "agent_user_memory_v2");

    private final DerivedIndexStoreAdmin admin;

    public DerivedIndexResetService(DerivedIndexStoreAdmin admin) {
        this.admin = Objects.requireNonNull(admin);
    }

    public void reset(String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("派生索引重置确认串不匹配");
        }
        for (String index : ES_INDEXES) {
            log.warn("derived_index_reset store=elasticsearch target={}", index);
            admin.deleteElasticsearchIndexIfExists(index);
        }
        for (String collection : MILVUS_COLLECTIONS) {
            log.warn("derived_index_reset store=milvus target={}", collection);
            admin.dropMilvusCollectionIfExists(collection);
        }
    }
}
