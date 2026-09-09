package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.service.DocumentUnit;

public record DocumentUnitResponse(
        long id,
        String unitType,
        int unitIndex,
        String locationLabel,
        String titlePath,
        String rawText,
        String effectiveText,
        Double ocrConfidence,
        boolean lowConfidence,
        int correctionRevision) {
    public static DocumentUnitResponse from(DocumentUnit unit) {
        return new DocumentUnitResponse(
                unit.id(), unit.unitType(), unit.unitIndex(), unit.locationLabel(), unit.titlePath(),
                unit.rawText(), unit.effectiveText(), unit.ocrConfidence(),
                unit.lowConfidence(), unit.correctionRevision());
    }
}
