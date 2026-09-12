package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusMemoryVectorIndexTest {

    @Test
    void createsIsolatedSchemaAndScopesEverySearchAndDelete() {
        MemoryMilvusGateway gateway = mock(MemoryMilvusGateway.class);
        UserMemoryIndexProperties properties = new UserMemoryIndexProperties();
        properties.getMilvus().setDimension(2);
        String collection = properties.getMilvus().getCollection();
        when(gateway.describe(collection)).thenReturn(null);
        MemoryIndexDocument document = new MemoryIndexDocument(
                "m-1", 1, 74680, 3, 2,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "用户常用工作范围是 Java 开发", 0.95,
                Instant.parse("2027-03-11T00:00:00Z"));
        List<Float> vector = List.of(0.1F, 0.2F);
        when(gateway.upsert(eq(collection), eq(new MemoryVectorRow(document, vector))))
                .thenReturn(1L);
        when(gateway.search(eq(collection), contains("tenant_id == 1"), eq(vector), eq(20)))
                .thenReturn(List.of(new MemorySearchHit(document, 0.88, "VECTOR")));
        MilvusMemoryVectorIndex index = new MilvusMemoryVectorIndex(
                gateway, properties,
                Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC));

        index.ensureReady();
        index.upsert(document, vector);
        var hits = index.search(1, 74680, 3, vector, 20);
        index.delete(1, 74680, 3, "m-1");
        index.deleteExplicitScope(1, 74680, 3);
        index.clearGeneration(1, 74680, 3);

        verify(gateway).create(new MemoryMilvusSpec(collection, 2, "COSINE", "memory_id"));
        verify(gateway).load(collection);
        verify(gateway).upsert(collection, new MemoryVectorRow(document, vector));
        verify(gateway).search(eq(collection), contains("user_id == 74680"), eq(vector), eq(20));
        verify(gateway).search(eq(collection), contains("memory_generation == 3"), eq(vector), eq(20));
        verify(gateway).search(eq(collection), contains("expires_at == 0"), eq(vector), eq(20));
        verify(gateway).delete(eq(collection), contains("memory_id == \"m-1\""));
        verify(gateway).delete(eq(collection), contains("source_type == \"USER_EXPLICIT\""));
        verify(gateway).delete(eq(collection), eq("tenant_id == 1 && user_id == 74680 && memory_generation == 3"));
        assertThat(hits).singleElement().extracting(MemorySearchHit::score).isEqualTo(0.88);
    }
}
