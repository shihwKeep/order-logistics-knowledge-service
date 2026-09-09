package com.xjjk.knowledge.document.service;

public class DocumentUnitRow {
    private Long id;
    private Long tenantId;
    private Long documentId;
    private Long versionId;
    private String unitType;
    private Integer unitIndex;
    private String locationLabel;
    private String titlePath;
    private String rawText;
    private String effectiveText;
    private Double ocrConfidence;
    private Boolean lowConfidence;
    private Integer correctionRevision;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getUnitType() { return unitType; }
    public void setUnitType(String unitType) { this.unitType = unitType; }
    public Integer getUnitIndex() { return unitIndex; }
    public void setUnitIndex(Integer unitIndex) { this.unitIndex = unitIndex; }
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
    public Boolean getLowConfidence() { return lowConfidence; }
    public void setLowConfidence(Boolean lowConfidence) { this.lowConfidence = lowConfidence; }
    public Integer getCorrectionRevision() { return correctionRevision; }
    public void setCorrectionRevision(Integer correctionRevision) { this.correctionRevision = correctionRevision; }
}
