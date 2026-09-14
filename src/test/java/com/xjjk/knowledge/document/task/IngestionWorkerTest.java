package com.xjjk.knowledge.document.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetExceededException;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.DocumentParser;
import com.xjjk.knowledge.document.parser.DocumentParserRegistry;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.parser.ParsedUnit;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.processing.ChunkingProperties;
import com.xjjk.knowledge.document.processing.StructuralChunker;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.retrieval.indexing.DraftIndexingService;
import com.xjjk.knowledge.observation.KnowledgeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngestionWorkerTest {

    @Test
    void saturatedBulkheadDoesNotClaimDatabaseLease() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        IngestionProperties properties = new IngestionProperties();
        properties.setMaxConcurrentTasks(1);
        IngestionBulkhead bulkhead = new IngestionBulkhead(properties);
        assertThat(bulkhead.tryAcquire()).isTrue();
        try {
            IngestionWorker worker = new IngestionWorker(
                    tasks, mock(DocumentRepository.class), mock(SourceObjectStore.class),
                    mock(DocumentParserRegistry.class), mock(IngestionArtifactRepository.class),
                    new StructuralChunker(new ChunkingProperties(), String::length),
                    mock(DraftIndexingService.class), properties, bulkhead, metrics());

            assertThat(worker.process(99L)).isFalse();

            verifyNoInteractions(tasks);
        } finally {
            bulkhead.release();
        }
    }

    @Test
    void releasesBulkheadPermitWhenClaimThrows() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        IngestionProperties properties = new IngestionProperties();
        when(tasks.claim(100L, properties.getWorkerId(), properties.getLeaseDuration()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(Optional.empty());
        IngestionBulkhead bulkhead = new IngestionBulkhead(properties);
        IngestionWorker worker = new IngestionWorker(
                tasks, mock(DocumentRepository.class), mock(SourceObjectStore.class),
                mock(DocumentParserRegistry.class), mock(IngestionArtifactRepository.class),
                new StructuralChunker(new ChunkingProperties(), String::length),
                mock(DraftIndexingService.class), properties, bulkhead, metrics());

        assertThatThrownBy(() -> worker.process(100L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(worker.process(100L)).isFalse();

        verify(tasks, times(2)).claim(
                100L, properties.getWorkerId(), properties.getLeaseDuration());
    }

    @Test
    void parsesReplacesArtifactsAndCompletesWithLeaseToken() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceObjectStore objects = mock(SourceObjectStore.class);
        DocumentParserRegistry parsers = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DraftIndexingService indexing = mock(DraftIndexingService.class);
        IngestionProperties properties = new IngestionProperties();
        properties.setLeaseDuration(Duration.ofSeconds(30));
        IngestionTaskLease lease = new IngestionTaskLease(7L, 1L, 2L, 3L, 4L, "PARSE", "token");
        when(tasks.claim(7L, properties.getWorkerId(), properties.getLeaseDuration())).thenReturn(Optional.of(lease));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(objects.get("source-key")).thenReturn(new ByteArrayInputStream("规则正文".getBytes()));
        when(parsers.select("txt", "text/plain")).thenReturn(parser);
        when(parser.parse(any())).thenReturn(new ParsedDocument(
                List.of(ParsedUnit.text(1, "正文", "退款规则", "七日内可退")), false));
        when(parser.version()).thenReturn("text-v1");
        when(tasks.complete(7L, "token")).thenReturn(true);
        StructuralChunker chunker = new StructuralChunker(new ChunkingProperties(), text -> text.length());
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, objects, parsers, artifacts, chunker, indexing, properties,
                new IngestionBulkhead(properties), metrics());

        assertThat(worker.process(7L)).isTrue();

        verify(artifacts).replaceParsedArtifacts(any(), any(), any(), any());
        verify(tasks).complete(7L, "token");
    }

    @Test
    void marksVersionFailedAndSchedulesRetryWhenRequiredPageOcrFails() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceObjectStore objects = mock(SourceObjectStore.class);
        DocumentParserRegistry parsers = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DraftIndexingService indexing = mock(DraftIndexingService.class);
        IngestionProperties properties = new IngestionProperties();
        IngestionTaskLease lease = new IngestionTaskLease(8L, 1L, 2L, 3L, 4L, "PARSE", "lease-8");
        when(tasks.claim(8L, properties.getWorkerId(), properties.getLeaseDuration())).thenReturn(Optional.of(lease));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(objects.get("source-key")).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(parsers.select("txt", "text/plain")).thenReturn(parser);
        doThrow(new com.xjjk.knowledge.common.error.BusinessException(
                com.xjjk.knowledge.common.api.ApiErrorCode.DOCUMENT_OCR_FAILED))
                .when(parser).parse(any());
        when(artifacts.markFailedIfOwned(any(), any(), any(), any())).thenReturn(true);
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, objects, parsers, artifacts,
                new StructuralChunker(new ChunkingProperties(), String::length), indexing, properties,
                new IngestionBulkhead(properties), metrics());

        assertThat(worker.process(8L)).isFalse();

        verify(artifacts).markFailedIfOwned(
                any(), org.mockito.ArgumentMatchers.eq(lease),
                org.mockito.ArgumentMatchers.eq("PARSE"),
                org.mockito.ArgumentMatchers.eq("DOCUMENT_OCR_FAILED"));
        verify(tasks).fail(
                8L, "lease-8", "DOCUMENT_OCR_FAILED", "文档文字识别失败",
                properties.getMaxRetries(), properties.getRetryBaseDelay());
    }

    @Test
    void indexesPreparedChunksAndCompletesIndexTask() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DraftIndexingService indexing = mock(DraftIndexingService.class);
        IngestionProperties properties = new IngestionProperties();
        properties.setLeaseDuration(Duration.ofMillis(90));
        IngestionTaskLease lease = new IngestionTaskLease(9L, 1L, 2L, 3L, 4L, "INDEX", "lease-9");
        when(tasks.claim(9L, properties.getWorkerId(), properties.getLeaseDuration())).thenReturn(Optional.of(lease));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(tasks.renew(9L, "lease-9", properties.getLeaseDuration())).thenReturn(true);
        when(artifacts.prepareIndexAttempt(any(), org.mockito.ArgumentMatchers.eq(lease))).thenReturn(true);
        when(tasks.complete(9L, "lease-9")).thenReturn(true);
        doAnswer(invocation -> {
            Thread.sleep(160);
            return null;
        }).when(indexing).index(any(), any(), anyLong(), any());
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, mock(SourceObjectStore.class), mock(DocumentParserRegistry.class),
                artifacts,
                new StructuralChunker(new ChunkingProperties(), String::length), indexing, properties,
                new IngestionBulkhead(properties), metrics());

        assertThat(worker.process(9L)).isTrue();

        verify(indexing).index(any(), any(), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("lease-9"));
        verify(artifacts).prepareIndexAttempt(any(), org.mockito.ArgumentMatchers.eq(lease));
        verify(tasks, atLeastOnce()).renew(9L, "lease-9", properties.getLeaseDuration());
        verify(tasks).complete(9L, "lease-9");
    }

    @Test
    void staleIndexWorkerCannotOverwriteNewerVersionStateWithFailed() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DraftIndexingService indexing = mock(DraftIndexingService.class);
        IngestionProperties properties = new IngestionProperties();
        IngestionTaskLease lease = new IngestionTaskLease(10L, 1L, 2L, 3L, 4L, "INDEX", "lease-10");
        when(tasks.claim(10L, properties.getWorkerId(), properties.getLeaseDuration())).thenReturn(Optional.of(lease));
        when(tasks.renew(10L, "lease-10", properties.getLeaseDuration())).thenReturn(true);
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(artifacts.prepareIndexAttempt(any(), org.mockito.ArgumentMatchers.eq(lease))).thenReturn(true);
        doThrow(new com.xjjk.knowledge.retrieval.indexing.IngestionLeaseLostException())
                .when(indexing).index(any(), any(), anyLong(), any());
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, mock(SourceObjectStore.class), mock(DocumentParserRegistry.class), artifacts,
                new StructuralChunker(new ChunkingProperties(), String::length), indexing, properties,
                new IngestionBulkhead(properties), metrics());

        assertThat(worker.process(10L)).isFalse();

        verify(artifacts, never()).markFailedIfOwned(any(), any(), any(), any());
        verify(tasks).complete(10L, "lease-10");
    }

    private static DocumentVersion version() {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.UPLOADED,
                "refund.txt", "txt", "text/plain", 12L, "sha", "source-key",
                null, null, null, null, null, null, null, null, false, 0, 0, 0,
                null, null, null, 10567L, now, now);
    }

    @Test
    void budgetExhaustionKeepsIndexTaskRetryableWithoutConsumingRetryLimitOrFailingVersion() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
        DraftIndexingService indexing = mock(DraftIndexingService.class);
        IngestionProperties properties = new IngestionProperties();
        IngestionTaskLease lease = new IngestionTaskLease(81L, 1L, 2L, 3L, 4L, "INDEX", "lease-81");
        when(tasks.claim(81L, properties.getWorkerId(), properties.getLeaseDuration()))
                .thenReturn(Optional.of(lease));
        when(tasks.renew(81L, "lease-81", properties.getLeaseDuration())).thenReturn(true);
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(artifacts.prepareIndexAttempt(any(), org.mockito.ArgumentMatchers.eq(lease))).thenReturn(true);
        doThrow(new CloudModelBudgetExceededException())
                .when(indexing).index(any(), any(), anyLong(), any());
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, mock(SourceObjectStore.class), mock(DocumentParserRegistry.class), artifacts,
                new StructuralChunker(new ChunkingProperties(), String::length), indexing, properties,
                new IngestionBulkhead(properties), metrics());

        assertThat(worker.process(81L)).isFalse();

        verify(tasks).defer(
                81L, "lease-81", "KNOWLEDGE_MODEL_BUDGET_EXHAUSTED",
                "知识检索模型本月硬额度已用尽", properties.getBudgetRetryDelay());
        verify(tasks, never()).fail(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(artifacts, never()).markFailedIfOwned(any(), any(), any(), any());
    }

    private static KnowledgeMetrics metrics() {
        return new KnowledgeMetrics(new SimpleMeterRegistry());
    }
}
