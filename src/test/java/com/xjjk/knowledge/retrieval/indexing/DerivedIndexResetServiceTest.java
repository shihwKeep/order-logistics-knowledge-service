package com.xjjk.knowledge.retrieval.indexing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DerivedIndexResetServiceTest {
    @Test
    void rejectsResetWithoutExactConfirmation() {
        DerivedIndexStoreAdmin admin = mock(DerivedIndexStoreAdmin.class);
        DerivedIndexResetService service = new DerivedIndexResetService(admin);

        assertThatThrownBy(() -> service.reset("wrong"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(admin);
    }

    @Test
    void deletesOnlyAllowlistedV1AndV2DerivedTargets() {
        DerivedIndexStoreAdmin admin = mock(DerivedIndexStoreAdmin.class);
        DerivedIndexResetService service = new DerivedIndexResetService(admin);

        service.reset(DerivedIndexResetService.CONFIRMATION);

        var order = inOrder(admin);
        for (String index : List.of("knowledge_chunks_draft_v1", "knowledge_chunks_published_v1",
                "knowledge_chunks_draft_v2", "knowledge_chunks_published_v2",
                "agent-user-memory-v1", "agent-user-memory-v2")) {
            order.verify(admin).deleteElasticsearchIndexIfExists(index);
        }
        for (String collection : List.of("knowledge_chunks_draft_v1", "knowledge_chunks_published_v1",
                "knowledge_chunks_draft_v2", "knowledge_chunks_published_v2",
                "agent_user_memory_v1", "agent_user_memory_v2")) {
            order.verify(admin).dropMilvusCollectionIfExists(collection);
        }
    }
}
