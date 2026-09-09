package com.xjjk.knowledge.document.persistence;

import java.time.LocalDateTime;

public class DocumentVersionEntity {
    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer versionNumber;
    private String status;
    private String originalFilename;
    private String fileExtension;
    private String mimeType;
    private Long fileSize;
    private String sourceSha256;
    private String sourceObjectKey;
    private String parsedObjectKey;
    private String parserVersion;
    private String chunkStrategyVersion;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String embeddingInstructionVersion;
    private String indexManifestSha256;
    private LocalDateTime indexedAt;
    private Boolean ocrRequired;
    private Integer correctionRevision;
    private Integer unitCount;
    private Integer chunkCount;
    private String failureStage;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String sourceSha256) { this.sourceSha256 = sourceSha256; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public void setSourceObjectKey(String sourceObjectKey) { this.sourceObjectKey = sourceObjectKey; }
    public String getParsedObjectKey() { return parsedObjectKey; }
    public void setParsedObjectKey(String parsedObjectKey) { this.parsedObjectKey = parsedObjectKey; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public String getChunkStrategyVersion() { return chunkStrategyVersion; }
    public void setChunkStrategyVersion(String chunkStrategyVersion) { this.chunkStrategyVersion = chunkStrategyVersion; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getEmbeddingInstructionVersion() { return embeddingInstructionVersion; }
    public void setEmbeddingInstructionVersion(String embeddingInstructionVersion) { this.embeddingInstructionVersion = embeddingInstructionVersion; }
    public String getIndexManifestSha256() { return indexManifestSha256; }
    public void setIndexManifestSha256(String indexManifestSha256) { this.indexManifestSha256 = indexManifestSha256; }
    public LocalDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; }
    public Boolean getOcrRequired() { return ocrRequired; }
    public void setOcrRequired(Boolean ocrRequired) { this.ocrRequired = ocrRequired; }
    public Integer getCorrectionRevision() { return correctionRevision; }
    public void setCorrectionRevision(Integer correctionRevision) { this.correctionRevision = correctionRevision; }
    public Integer getUnitCount() { return unitCount; }
    public void setUnitCount(Integer unitCount) { this.unitCount = unitCount; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
