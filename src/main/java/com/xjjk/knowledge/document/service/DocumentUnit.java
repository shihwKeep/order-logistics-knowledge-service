package com.xjjk.knowledge.document.service;

public record DocumentUnit(
        long id,
        long tenantId,
        long documentId,
        long versionId,
        String unitType,
        int unitIndex,
        String locationLabel,
        String titlePath,
        String rawText,
        String effectiveText,
        Double ocrConfidence,
        boolean lowConfidence,
        int correctionRevision) {}
