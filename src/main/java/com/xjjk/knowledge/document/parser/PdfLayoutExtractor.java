package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.document.ocr.OcrBlock;
import com.xjjk.knowledge.document.ocr.OcrResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/** 从 PDF 文本层提取视觉行，保留字体和坐标供结构分析使用。 */
@Component
public class PdfLayoutExtractor {

    public PdfPageLayout extract(PDDocument document, int pageIndex) throws IOException {
        PositionCollector collector = new PositionCollector();
        collector.setSortByPosition(true);
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(document);

        PDPage page = document.getPage(pageIndex);
        double pageWidth = page.getCropBox().getWidth();
        double pageHeight = page.getCropBox().getHeight();
        List<PdfTextLine> lines = groupLines(collector.positions(), pageWidth, pageHeight);
        String rawText = String.join("\n", lines.stream().map(PdfTextLine::text).toList());
        return new PdfPageLayout(
                pageIndex + 1, pageWidth, pageHeight, rawText, lines, null, false);
    }

    /**
     * OCR 坐标属于渲染图片像素空间，必须按 X/Y 比例换算到 PDF 页面坐标，
     * 否则页边判断和行间距都会随渲染 DPI 改变。
     */
    public PdfPageLayout fromOcr(
            int pageNumber,
            double pageWidth,
            double pageHeight,
            int imageWidth,
            int imageHeight,
            OcrResult result) {
        double scaleX = imageWidth <= 0 ? 1D : pageWidth / imageWidth;
        double scaleY = imageHeight <= 0 ? 1D : pageHeight / imageHeight;
        List<PdfTextLine> lines = new ArrayList<>();
        int fallbackIndex = 0;
        for (OcrBlock block : result.blocks()) {
            if (block.text() == null || block.text().isBlank()) {
                continue;
            }
            Box box = box(block, scaleX, scaleY, fallbackIndex++, pageWidth);
            lines.add(new PdfTextLine(
                    block.text().strip(), box.x(), box.y(), box.width(), box.height(),
                    Math.max(1D, box.height()), "OCR", false, pageWidth, pageHeight));
        }
        String rawText = String.join("\n", lines.stream().map(PdfTextLine::text).toList());
        return new PdfPageLayout(
                pageNumber, pageWidth, pageHeight, rawText, lines,
                result.averageConfidence(), result.hasLowConfidence());
    }

    private Box box(
            OcrBlock block,
            double scaleX,
            double scaleY,
            int fallbackIndex,
            double pageWidth) {
        List<List<Integer>> points = block.box();
        if (points.size() >= 2 && points.stream().allMatch(point -> point.size() >= 2)) {
            double minX = points.stream().mapToDouble(point -> point.get(0)).min().orElse(0D) * scaleX;
            double maxX = points.stream().mapToDouble(point -> point.get(0)).max().orElse(0D) * scaleX;
            double minY = points.stream().mapToDouble(point -> point.get(1)).min().orElse(0D) * scaleY;
            double maxY = points.stream().mapToDouble(point -> point.get(1)).max().orElse(0D) * scaleY;
            return new Box(minX, minY, Math.max(0D, maxX - minX), Math.max(1D, maxY - minY));
        }
        // 无坐标时只保留 OCR 返回顺序，并使用中性宽度避免凭行长猜测标题。
        double y = 40D + fallbackIndex * 20D;
        return new Box(40D, y, Math.max(1D, pageWidth * 0.80D), 12D);
    }

    /**
     * PDFBox 的回调边界不等于视觉行，因此先收集字形，再按基线容差聚合。
     * 这里仅处理单栏自然阅读顺序；复杂多栏版面留给后续专用版面模型。
     */
    private List<PdfTextLine> groupLines(
            List<TextPosition> source, double pageWidth, double pageHeight) {
        List<TextPosition> ordered = source.stream()
                .filter(position -> position.getUnicode() != null && !position.getUnicode().isEmpty())
                .sorted(Comparator.comparingDouble(TextPosition::getYDirAdj)
                        .thenComparingDouble(TextPosition::getXDirAdj))
                .toList();
        List<List<TextPosition>> grouped = new ArrayList<>();
        for (TextPosition position : ordered) {
            if (grouped.isEmpty() || !sameLine(grouped.getLast(), position)) {
                grouped.add(new ArrayList<>());
            }
            grouped.getLast().add(position);
        }
        return grouped.stream()
                .map(line -> toLine(line, pageWidth, pageHeight))
                .filter(line -> !line.text().isBlank())
                .toList();
    }

    private boolean sameLine(List<TextPosition> current, TextPosition candidate) {
        TextPosition anchor = current.getFirst();
        double tolerance = Math.max(1.5D,
                Math.min(anchor.getHeightDir(), candidate.getHeightDir()) * 0.45D);
        return Math.abs(anchor.getYDirAdj() - candidate.getYDirAdj()) <= tolerance;
    }

    private PdfTextLine toLine(
            List<TextPosition> positions, double pageWidth, double pageHeight) {
        List<TextPosition> ordered = positions.stream()
                .sorted(Comparator.comparingDouble(TextPosition::getXDirAdj))
                .toList();
        StringBuilder text = new StringBuilder();
        TextPosition previous = null;
        for (TextPosition position : ordered) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                continue;
            }
            if (previous != null && needsSyntheticSpace(previous, position, text)) {
                text.append(' ');
            }
            appendNormalized(text, unicode);
            previous = position;
        }

        double minX = ordered.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0D);
        double maxX = ordered.stream()
                .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
                .max().orElse(minX);
        double minY = ordered.stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0D);
        double maxHeight = ordered.stream().mapToDouble(TextPosition::getHeightDir).max().orElse(0D);
        double fontSize = ordered.stream().mapToDouble(TextPosition::getFontSizeInPt).max().orElse(0D);
        String fontName = ordered.stream()
                .filter(position -> position.getFont() != null)
                .map(position -> position.getFont().getName())
                .findFirst()
                .orElse("");
        boolean bold = ordered.stream()
                .filter(position -> position.getFont() != null)
                .map(position -> position.getFont().getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("bold") || name.contains("black") || name.contains("demi"));
        return new PdfTextLine(
                text.toString().strip(), minX, minY, maxX - minX, maxHeight,
                fontSize, fontName, bold, pageWidth, pageHeight);
    }

    private boolean needsSyntheticSpace(
            TextPosition previous, TextPosition current, StringBuilder text) {
        if (text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1))) {
            return false;
        }
        double gap = current.getXDirAdj()
                - (previous.getXDirAdj() + previous.getWidthDirAdj());
        double normalSpace = positiveMinimum(previous.getWidthOfSpace(), current.getWidthOfSpace());
        double threshold = normalSpace > 0D ? normalSpace * 0.45D : 1.5D;
        return gap > Math.max(1D, threshold);
    }

    private double positiveMinimum(double left, double right) {
        if (left <= 0D) {
            return right;
        }
        if (right <= 0D) {
            return left;
        }
        return Math.min(left, right);
    }

    private void appendNormalized(StringBuilder target, String source) {
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (Character.isWhitespace(value)) {
                if (!target.isEmpty() && !Character.isWhitespace(target.charAt(target.length() - 1))) {
                    target.append(' ');
                }
            } else {
                target.append(value);
            }
        }
    }

    private static final class PositionCollector extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        private PositionCollector() throws IOException {
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            positions.add(text);
            super.processTextPosition(text);
        }

        private List<TextPosition> positions() {
            return List.copyOf(positions);
        }
    }

    private record Box(double x, double y, double width, double height) {
    }
}
