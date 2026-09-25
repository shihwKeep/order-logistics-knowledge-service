package com.xjjk.knowledge.document.parser;

/** PDF 页面上的一条视觉行；坐标使用 PDFBox 旋转校正后的页面坐标。 */
public record PdfTextLine(
        String text,
        double x,
        double y,
        double width,
        double height,
        double fontSize,
        String fontName,
        boolean bold,
        double pageWidth,
        double pageHeight) {

    public double yRatio() {
        return pageHeight <= 0D ? 0D : y / pageHeight;
    }
}
