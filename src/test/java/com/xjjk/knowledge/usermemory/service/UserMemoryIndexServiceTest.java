package com.xjjk.knowledge.usermemory.service;

import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexOperation;
import com.xjjk.knowledge.usermemory.index.MemoryKeywordIndex;
import com.xjjk.knowledge.usermemory.index.MemoryVectorIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryIndexServiceTest {

    @Test
    void embedsAndWritesBothIndexesAndSupportsAllDeleteScopes() {
        MemoryKeywordIndex keyword = mock(MemoryKeywordIndex.class);
        MemoryVectorIndex vector = mock(MemoryVectorIndex.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        UserMemoryIndexProperties properties = new UserMemoryIndexProperties();
        properties.setEnabled(true);
        MemoryIndexDocument document = new MemoryIndexDocument(
                "m-1", 1, 74680, 3, 2,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "用户常用工作范围是 Java 开发", 0.95,
                Instant.parse("2027-03-11T00:00:00Z"));
        List<Float> embedding = java.util.Collections.nCopies(2560, 0.01F);
        when(embeddings.embedDocuments(List.of(document.content())))
                .thenReturn(List.of(embedding));
        UserMemoryIndexService service = new UserMemoryIndexService(
                properties, keyword, vector, embeddings);

        service.apply(MemoryIndexOperation.UPSERT, document);
        service.delete(1, 74680, 3, "m-1");
        service.deleteExplicitScope(1, 74680, 3);
        service.clearGeneration(1, 74680, 3);

        var order = inOrder(keyword, vector);
        order.verify(keyword).upsert(document);
        order.verify(vector).upsert(document, embedding);
        verify(keyword).delete(1, 74680, 3, "m-1");
        verify(vector).delete(1, 74680, 3, "m-1");
        verify(keyword).deleteExplicitScope(1, 74680, 3);
        verify(vector).deleteExplicitScope(1, 74680, 3);
        verify(keyword).clearGeneration(1, 74680, 3);
        verify(vector).clearGeneration(1, 74680, 3);
    }
}
