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
        int updated = mapper.markReady(
                version.tenantId(), version.documentId(), version.id(), version.chunkCount(),
                metadata.model(), metadata.dimension(), metadata.instructionVersion(),
                metadata.manifestSha256());
        if (updated != 1) {
            throw new IllegalStateException("文档版本不存在或 Chunk 数量已发生变化，拒绝标记 READY");
        }
    }
}
