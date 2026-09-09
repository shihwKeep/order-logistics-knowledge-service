package com.xjjk.knowledge.document.service;

/** 在校正事务中锁定的版本修订号与发布状态。 */
public class CorrectionVersionState {
    private Integer correctionRevision;
    private String status;

    public CorrectionVersionState() {
    }

    public CorrectionVersionState(Integer correctionRevision, String status) {
        this.correctionRevision = correctionRevision;
        this.status = status;
    }

    public int getCorrectionRevision() {
        return correctionRevision == null ? 0 : correctionRevision;
    }

    public void setCorrectionRevision(Integer correctionRevision) {
        this.correctionRevision = correctionRevision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
