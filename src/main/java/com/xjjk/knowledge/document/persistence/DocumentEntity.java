package com.xjjk.knowledge.document.persistence;

import java.time.LocalDateTime;

public class DocumentEntity {
    private Long id;
    private Long tenantId;
    private Long knowledgeBaseId;
    private String title;
    private Long currentDraftVersionId;
    private Long currentPublishedVersionId;
    private Long createdBy;
    private Long updatedBy;
    private Integer rowVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getCurrentDraftVersionId() { return currentDraftVersionId; }
    public void setCurrentDraftVersionId(Long currentDraftVersionId) { this.currentDraftVersionId = currentDraftVersionId; }
    public Long getCurrentPublishedVersionId() { return currentPublishedVersionId; }
    public void setCurrentPublishedVersionId(Long currentPublishedVersionId) { this.currentPublishedVersionId = currentPublishedVersionId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public Integer getRowVersion() { return rowVersion; }
    public void setRowVersion(Integer rowVersion) { this.rowVersion = rowVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
