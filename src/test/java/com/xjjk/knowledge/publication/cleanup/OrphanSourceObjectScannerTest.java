package com.xjjk.knowledge.publication.cleanup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.document.storage.DocumentStorageProperties;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.document.storage.StoredSourceObject;
import com.xjjk.knowledge.document.task.IngestionArtifactRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrphanSourceObjectScannerTest {
    @Test
    void deletesOnlyOldUnreferencedSourceObjects() {
        SourceObjectStore store = mock(SourceObjectStore.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setOrphanSafetyWindow(Duration.ofHours(24));
        properties.setOrphanScanBatchSize(100);
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        StoredSourceObject orphan = new StoredSourceObject(
                "tenant/1/knowledge-base/2/document/3/version/4/source",
                now.minus(Duration.ofHours(25)));
        StoredSourceObject referenced = new StoredSourceObject(
                "tenant/1/knowledge-base/2/document/3/version/5/source",
                now.minus(Duration.ofDays(2)));
        StoredSourceObject recent = new StoredSourceObject(
                "tenant/1/knowledge-base/2/document/3/version/6/source",
                now.minus(Duration.ofHours(2)));
        Instant cutoff = now.minus(Duration.ofHours(24));
        when(store.listSourceObjectsOlderThan(cutoff, 100))
                .thenReturn(List.of(orphan, referenced, recent));
        when(artifacts.isSourceObjectReferenced(orphan.key())).thenReturn(false);
        when(artifacts.isSourceObjectReferenced(referenced.key())).thenReturn(true);
        OrphanSourceObjectScanner scanner = new OrphanSourceObjectScanner(
                store, artifacts, properties, Clock.fixed(now, ZoneOffset.UTC));

        scanner.scan();

        verify(store).delete(orphan.key());
        verify(store, never()).delete(referenced.key());
        verify(store, never()).delete(recent.key());
    }
}
