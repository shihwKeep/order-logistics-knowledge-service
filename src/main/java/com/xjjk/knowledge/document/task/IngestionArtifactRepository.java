package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.parser.ParsedUnit;
import com.xjjk.knowledge.document.processing.DocumentChunk;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IngestionArtifactRepository {
    private final IngestionArtifactMapper mapper;
    private final TextNormalizer normalizer;

    public IngestionArtifactRepository(IngestionArtifactMapper mapper, TextNormalizer normalizer) {
        this.mapper = mapper;
        this.normalizer = normalizer;
    }

    /** 按版本幂等替换全部派生数据，原始文件和版本身份保持不变。 */
    @Transactional
    public void replaceParsedArtifacts(
            DocumentVersion version,
            ParsedDocument parsed,
            List<DocumentChunk> chunks,
            String parserVersion) {
        mapper.deleteChunks(version.tenantId(), version.id());
        mapper.deleteUnits(version.tenantId(), version.id());
        Map<Integer, Long> unitIds = new HashMap<>();
        for (ParsedUnit unit : parsed.units()) {
            DocumentUnitEntity entity = toEntity(version, unit);
            mapper.insertUnit(entity);
            unitIds.put(unit.unitIndex(), entity.getId());
        }
        insertChunks(version, chunks, unitIds);
        mapper.markParsed(
                version.tenantId(), version.documentId(), version.id(), parserVersion,
                parsed.ocrRequired(), parsed.units().size(), chunks.size());
        enqueueIndex(version);
    }

    public ParsedDocument loadEffectiveUnits(DocumentVersion version) {
        List<ParsedUnit> units = mapper.listUnits(version.tenantId(), version.documentId(), version.id())
                .stream()
                .map(entity -> new ParsedUnit(
                        entity.getUnitType(), entity.getUnitIndex(), entity.getLocationLabel(), entity.getTitlePath(),
                        entity.getEffectiveText(), entity.getOcrConfidence(), entity.isLowConfidence()))
                .toList();
        return new ParsedDocument(units, version.ocrRequired());
    }

    @Transactional
    public void replaceChunks(DocumentVersion version, List<DocumentChunk> chunks) {
        mapper.deleteChunks(version.tenantId(), version.id());
        Map<Integer, Long> unitIds = new HashMap<>();
        mapper.listUnits(version.tenantId(), version.documentId(), version.id())
                .forEach(entity -> unitIds.put(entity.getUnitIndex(), entity.getId()));
        insertChunks(version, chunks, unitIds);
        mapper.markRechunked(version.tenantId(), version.documentId(), version.id(), chunks.size());
        enqueueIndex(version);
    }

    /** 自动重试 INDEX 时，仅允许当前租约把同一人工校正修订从 FAILED 恢复到 INDEXING。 */
    public boolean prepareIndexAttempt(DocumentVersion version, IngestionTaskLease lease) {
        return mapper.prepareIndexAttempt(
                version.tenantId(), version.documentId(), version.id(), version.correctionRevision(),
                lease.taskId(), lease.leaseToken()) == 1;
    }

    /** 失败状态同样受修订号和任务租约保护，陈旧 Worker 不得覆盖新校正或 READY。 */
    public boolean markFailedIfOwned(
            DocumentVersion version, IngestionTaskLease lease, String stage, String errorCode) {
        return mapper.markFailedIfOwned(
                version.tenantId(), version.documentId(), version.id(), version.correctionRevision(),
                lease.taskId(), lease.leaseToken(), stage, errorCode) == 1;
    }

    private void insertChunks(DocumentVersion version, List<DocumentChunk> chunks, Map<Integer, Long> unitIds) {
        for (DocumentChunk chunk : chunks) {
            Long unitId = unitIds.get(chunk.sourceUnitIndex());
            if (unitId == null) {
                throw new IllegalStateException("Chunk 找不到来源单元: " + chunk.sourceUnitIndex());
            }
            mapper.insertChunk(
                    version.tenantId(), version.knowledgeBaseId(), version.documentId(), version.id(),
                    unitId, chunk.chunkIndex(), chunk.titlePath(), chunk.text(), chunk.estimatedTokens(),
                    chunk.sha256(), "{\"locationLabel\":\"" + jsonEscape(chunk.locationLabel()) + "\"}");
        }
    }

    /**
     * INDEX 任务按“版本 + 人工校正修订号”幂等登记。只有首次插入任务时才生成 Outbox，
     * 避免 Worker 在完成回写前崩溃并重跑时重复制造唤醒事件。
     */
    private void enqueueIndex(DocumentVersion version) {
        String taskKey = "INDEX:" + version.tenantId() + ":" + version.id() + ":" + version.correctionRevision();
        if (mapper.insertIndexTask(
                version.tenantId(), version.knowledgeBaseId(), version.documentId(), version.id(), taskKey) == 1) {
            mapper.insertIndexOutbox(
                    UUID.randomUUID().toString(), version.tenantId(), Long.toString(version.id()),
                    "{\"tenantId\":" + version.tenantId() + ",\"versionId\":" + version.id()
                            + ",\"stage\":\"INDEX\"}");
        }
    }

    private DocumentUnitEntity toEntity(DocumentVersion version, ParsedUnit unit) {
        DocumentUnitEntity entity = new DocumentUnitEntity();
        entity.setTenantId(version.tenantId());
        entity.setDocumentId(version.documentId());
        entity.setVersionId(version.id());
        entity.setUnitType(unit.unitType());
        entity.setUnitIndex(unit.unitIndex());
        entity.setLocationLabel(unit.locationLabel());
        entity.setTitlePath(unit.titlePath());
        entity.setRawText(unit.rawText());
        entity.setEffectiveText(normalizer.normalize(unit.text()));
        entity.setOcrConfidence(unit.ocrConfidence());
        entity.setLowConfidence(unit.lowConfidence());
        entity.setContentSha256(normalizer.sha256(entity.getEffectiveText()));
        return entity;
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
