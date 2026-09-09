package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class MybatisChunkIndexRepository implements ChunkIndexRepository {
    private final ChunkIndexMapper mapper;

    public MybatisChunkIndexRepository(ChunkIndexMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<IndexChunk> loadVersionChunks(DocumentVersion version) {
        return mapper.listVersionChunks(version.tenantId(), version.documentId(), version.id());
    }

    @Override
    @Transactional
    public void markReady(DocumentVersion version, ReadyIndexMetadata metadata) {
        markReady(version, metadata, null, null);
    }

    @Override
    @Transactional
    public void markReady(
            DocumentVersion version, ReadyIndexMetadata metadata, Long taskId, String leaseToken) {
        int updated = mapper.markReady(
                version.tenantId(), version.documentId(), version.id(), version.chunkCount(),
                version.correctionRevision(),
                metadata.model(), metadata.dimension(), metadata.instructionVersion(),
                metadata.manifestSha256(), taskId, leaseToken);
        if (updated != 1) {
            throw new IllegalStateException(
                    "文档版本状态、校正修订号或 Chunk 数量已发生变化，拒绝标记 READY");
        }
    }
}
