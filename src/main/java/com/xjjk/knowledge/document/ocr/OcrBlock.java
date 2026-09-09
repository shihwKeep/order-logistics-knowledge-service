package com.xjjk.knowledge.document.ocr;

import java.util.List;

/** OCR 文本块保留坐标和置信度，便于管理端定位并提示人工复核。 */
public record OcrBlock(String text, double confidence, List<List<Integer>> box, boolean lowConfidence) {
    public OcrBlock {
        box = box == null ? List.of() : box.stream().map(List::copyOf).toList();
    }
}
