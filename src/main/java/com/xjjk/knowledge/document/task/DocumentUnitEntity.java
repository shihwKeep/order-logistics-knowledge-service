package com.xjjk.knowledge.document.task;

public class DocumentUnitEntity {
    private Long id;
    private long tenantId;
    private long documentId;
    private long versionId;
    private String unitType;
    private int unitIndex;
    private String locationLabel;
    private String titlePath;
    private String rawText;
    private String effectiveText;
    private Double ocrConfidence;
    private boolean lowConfidence;
    private String contentSha256;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getTenantId() { return tenantId; }
    public void setTenantId(long tenantId) { this.tenantId = tenantId; }
    public long getDocumentId() { return documentId; }
    public void setDocumentId(long documentId) { this.documentId = documentId; }
    public long getVersionId() { return versionId; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public String getUnitType() { return unitType; }
    public void setUnitType(String unitType) { this.unitType = unitType; }
    public int getUnitIndex() { return unitIndex; }
    public void setUnitIndex(int unitIndex) { this.unitIndex = unitIndex; }
    public String getLocationLabel() { return locationLabel; }
    public void setLocationLabel(String locationLabel) { this.locationLabel = locationLabel; }
    public String getTitlePath() { return titlePath; }
    public void setTitlePath(String titlePath) { this.titlePath = titlePath; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getEffectiveText() { return effectiveText; }
    public void setEffectiveText(String effectiveText) { this.effectiveText = effectiveText; }
    public Double getOcrConfidence() { return ocrConfidence; }
    public void setOcrConfidence(Double ocrConfidence) { this.ocrConfidence = ocrConfidence; }
    public boolean isLowConfidence() { return lowConfidence; }
    public void setLowConfidence(boolean lowConfidence) { this.lowConfidence = lowConfidence; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
}
