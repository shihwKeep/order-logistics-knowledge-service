package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.model.IndexChunk;

import java.util.List;

/** MySQL 是 Chunk 正文和版本索引状态的唯一事实源。 */
public interface ChunkIndexRepository {
    List<IndexChunk> loadVersionChunks(DocumentVersion version);

    void markReady(DocumentVersion version, ReadyIndexMetadata metadata);
}
