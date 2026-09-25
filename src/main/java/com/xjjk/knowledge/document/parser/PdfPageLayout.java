package com.xjjk.knowledge.document.parser;

import java.util.List;

/** 单页版面事实；标题和页眉页脚判断由后续组件完成。 */
public record PdfPageLayout(
        int pageNumber,
        double width,
        double height,
        String rawText,
        List<PdfTextLine> lines,
        Double ocrConfidence,
        boolean lowConfidence) {

    public PdfPageLayout {
        rawText = rawText == null ? "" : rawText;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
