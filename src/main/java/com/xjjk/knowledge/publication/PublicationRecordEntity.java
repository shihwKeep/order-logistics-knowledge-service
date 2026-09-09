package com.xjjk.knowledge.publication;

import java.time.LocalDateTime;

public class PublicationRecordEntity {
    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long fromVersionId;
    private Long toVersionId;
    private String action;
    private Long actorUserId;
    private String requestId;
    private Integer chunkCount;
    private String manifestSha256;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getFromVersionId() { return fromVersionId; }
    public void setFromVersionId(Long fromVersionId) { this.fromVersionId = fromVersionId; }
    public Long getToVersionId() { return toVersionId; }
    public void setToVersionId(Long toVersionId) { this.toVersionId = toVersionId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getManifestSha256() { return manifestSha256; }
    public void setManifestSha256(String manifestSha256) { this.manifestSha256 = manifestSha256; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
