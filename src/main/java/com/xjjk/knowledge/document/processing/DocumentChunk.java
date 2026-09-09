package com.xjjk.knowledge.document.processing;

public record DocumentChunk(
        String identity,
        int chunkIndex,
        int sourceUnitIndex,
        String locationLabel,
        String titlePath,
        String text,
        int estimatedTokens,
        String sha256) {}
