package com.xjjk.knowledge.document.ocr;

import java.util.List;

public record OcrResult(String requestId, int rotation, List<OcrBlock> blocks) {
    public OcrResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public String joinedText() {
        return blocks.stream().map(OcrBlock::text).filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    public Double averageConfidence() {
        return blocks.isEmpty() ? null : blocks.stream().mapToDouble(OcrBlock::confidence).average().orElse(0D);
    }

    public boolean hasLowConfidence() {
        return blocks.stream().anyMatch(OcrBlock::lowConfidence);
    }
}
