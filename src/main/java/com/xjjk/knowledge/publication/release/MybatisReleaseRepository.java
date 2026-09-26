package com.xjjk.knowledge.publication.release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisReleaseRepository implements ReleaseRepository {
    private final ReleaseMapper mapper;

    public MybatisReleaseRepository(ReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReleaseBaseline lockBaseline(long tenantId, long knowledgeBaseId) {
        return mapper.lockBaseline(tenantId, knowledgeBaseId);
    }

    @Override
    public List<ReleaseItem> items(long releaseId) {
        return mapper.items(releaseId);
    }

    @Override
    public Optional<ReleaseItem> findReadyItem(
            long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        return Optional.ofNullable(mapper.findReadyItem(
                tenantId, knowledgeBaseId, documentId, versionId));
    }

    @Override
    public Optional<KnowledgeRelease> findByRequest(long tenantId, String requestId) {
        return Optional.ofNullable(mapper.findByRequest(tenantId, requestId));
    }

    @Override
    public int nextReleaseNumber(long tenantId, long knowledgeBaseId) {
        return mapper.nextReleaseNumber(tenantId, knowledgeBaseId);
    }

    @Override
    public KnowledgeRelease insert(KnowledgeRelease release) {
        mapper.insertRelease(release);
        KnowledgeRelease inserted = mapper.findByRequest(release.tenantId(), release.requestId());
        if (inserted == null) {
            throw new IllegalStateException("Release 写入后无法读取");
        }
        return inserted;
    }

    @Override
    public void insertItems(long releaseId, List<ReleaseItem> items) {
        items.forEach(item -> mapper.insertItem(releaseId, item));
    }

    @Override
    public void enqueue(KnowledgeRelease release) {
        mapper.insertTask(release);
        String payload = "{\"tenantId\":" + release.tenantId()
                + ",\"knowledgeBaseId\":" + release.knowledgeBaseId()
                + ",\"releaseId\":" + release.id() + "}";
        mapper.insertOutbox(
                UUID.randomUUID().toString(), Long.toString(release.id()), payload, release);
    }
}
