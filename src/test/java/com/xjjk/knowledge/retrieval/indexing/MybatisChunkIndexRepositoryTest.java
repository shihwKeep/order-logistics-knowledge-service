package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisChunkIndexRepositoryTest {
    @Test
    void leasedReadyPreconditionFailureIsTreatedAsStaleWorker() {
        ChunkIndexMapper mapper = mock(ChunkIndexMapper.class);
        when(mapper.markReady(anyLong(), anyLong(), anyLong(),
                anyInt(), anyInt(), any(String.class), anyInt(),
                any(String.class), any(String.class), any(Long.class), any(String.class))).thenReturn(0);
        MybatisChunkIndexRepository repository = new MybatisChunkIndexRepository(mapper);

        assertThatThrownBy(() -> repository.markReady(
                version(), new ReadyIndexMetadata("qwen", 2560, "instruction", "manifest"), 9L, "lease"))
                .isInstanceOf(IngestionLeaseLostException.class);
    }

    @Test
    void promotesLatestReadyVersionAndRegistersReplacedDraftCleanup() {
        ChunkIndexMapper mapper = mock(ChunkIndexMapper.class);
        when(mapper.lockCurrentDraft(1L, 2L, 3L)).thenReturn(8L);
        when(mapper.markReady(anyLong(), anyLong(), anyLong(),
                anyInt(), anyInt(), any(String.class), anyInt(),
                any(String.class), any(String.class), any(Long.class), any(String.class))).thenReturn(1);
        when(mapper.promoteLatestDraft(1L, 2L, 3L, 4L, 1, 1L)).thenReturn(1);
        MybatisChunkIndexRepository repository = new MybatisChunkIndexRepository(mapper);

        repository.markReady(
                version(), new ReadyIndexMetadata("qwen", 2560, "instruction", "manifest"), 9L, "lease");

        verify(mapper).insertDraftCleanup(1L, 2L, 3L, 8L, "DRAFT_REPLACED");
    }

    @Test
    void delayedOlderVersionBecomesReadyWithoutOverwritingNewerDraft() {
        ChunkIndexMapper mapper = mock(ChunkIndexMapper.class);
        when(mapper.lockCurrentDraft(1L, 2L, 3L)).thenReturn(8L);
        when(mapper.markReady(anyLong(), anyLong(), anyLong(),
                anyInt(), anyInt(), any(String.class), anyInt(),
                any(String.class), any(String.class), any(Long.class), any(String.class))).thenReturn(1);
        when(mapper.promoteLatestDraft(1L, 2L, 3L, 4L, 1, 1L)).thenReturn(0);
        MybatisChunkIndexRepository repository = new MybatisChunkIndexRepository(mapper);

        repository.markReady(
                version(), new ReadyIndexMetadata("qwen", 2560, "instruction", "manifest"), 9L, "lease");

        verify(mapper, never()).insertDraftCleanup(
                anyLong(), anyLong(), anyLong(), anyLong(), any(String.class));
    }

    private DocumentVersion version() {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.INDEXING,
                "a.txt", "txt", "text/plain", 1L, "sha", "key", null,
                "text-v1", "structural-v1", null, null, null, null, null,
                false, 2, 1, 1, null, null, null, 1L, now, now);
    }
}
