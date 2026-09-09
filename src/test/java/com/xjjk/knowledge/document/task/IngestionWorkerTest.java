package com.xjjk.knowledge.document.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.DocumentParser;
import com.xjjk.knowledge.document.parser.DocumentParserRegistry;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.parser.ParsedUnit;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.processing.ChunkingProperties;
import com.xjjk.knowledge.document.processing.StructuralChunker;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngestionWorkerTest {

    @Test
    void parsesReplacesArtifactsAndCompletesWithLeaseToken() {
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceObjectStore objects = mock(SourceObjectStore.class);
        DocumentParserRegistry parsers = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        IngestionArtifactRepository artifacts = mock(IngestionArtifactRepository.class);
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
                tasks, documents, objects, parsers, artifacts, chunker, properties);

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
        IngestionProperties properties = new IngestionProperties();
        IngestionTaskLease lease = new IngestionTaskLease(8L, 1L, 2L, 3L, 4L, "PARSE", "lease-8");
        when(tasks.claim(8L, properties.getWorkerId(), properties.getLeaseDuration())).thenReturn(Optional.of(lease));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version()));
        when(objects.get("source-key")).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(parsers.select("txt", "text/plain")).thenReturn(parser);
        doThrow(new com.xjjk.knowledge.common.error.BusinessException(
                com.xjjk.knowledge.common.api.ApiErrorCode.DOCUMENT_OCR_FAILED))
                .when(parser).parse(any());
        IngestionWorker worker = new IngestionWorker(
                tasks, documents, objects, parsers, artifacts,
                new StructuralChunker(new ChunkingProperties(), String::length), properties);

        assertThat(worker.process(8L)).isFalse();

        verify(artifacts).markFailed(1L, 3L, 4L, "PARSE", "DOCUMENT_OCR_FAILED");
        verify(tasks).fail(
                8L, "lease-8", "DOCUMENT_OCR_FAILED", "文档文字识别失败",
                properties.getMaxRetries(), properties.getRetryBaseDelay());
    }

    private static DocumentVersion version() {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.UPLOADED,
                "refund.txt", "txt", "text/plain", 12L, "sha", "source-key",
                null, null, null, null, null, null, null, null, false, 0, 0, 0,
                null, null, null, 10567L, now, now);
    }
}
