package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.parser.ParsedUnit;
import com.xjjk.knowledge.document.processing.DocumentChunk;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        mapper.markParsed(
                version.tenantId(), version.documentId(), version.id(), parserVersion,
                parsed.ocrRequired(), parsed.units().size(), chunks.size());
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
        entity.setRawText(unit.text());
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
