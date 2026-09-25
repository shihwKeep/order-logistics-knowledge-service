package com.xjjk.knowledge.document.parser;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRepeatedArtifactDetectorTest {
    private final PdfRepeatedArtifactDetector detector = new PdfRepeatedArtifactDetector();

    @Test
    void removesOnlyRepeatedTopAndBottomLines() {
        Set<PdfTextLine> artifacts = detector.detect(List.of(
                page(1, "Policy Manual", "Unique cover title", "Page 1"),
                page(2, "Policy Manual", "Refund body", "Page 2"),
                page(3, "Policy Manual", "Logistics body", "Page 3")));

        assertThat(texts(artifacts)).contains("Policy Manual", "Page 1", "Page 2", "Page 3");
        assertThat(texts(artifacts))
                .doesNotContain("Unique cover title", "Refund body", "Logistics body");
    }

    @Test
    void doesNotDeleteRepeatedBodyTextOutsideMarginBands() {
        List<PdfPageLayout> pages = List.of(
                pageWithBody(1, "相同业务正文"),
                pageWithBody(2, "相同业务正文"),
                pageWithBody(3, "相同业务正文"));

        assertThat(detector.detect(pages)).isEmpty();
    }

    @Test
    void leavesTwoPageDocumentConservative() {
        List<PdfPageLayout> pages = List.of(
                page(1, "Shared top text", "First body", "Footer"),
                page(2, "Shared top text", "Second body", "Footer"));

        assertThat(detector.detect(pages)).isEmpty();
    }

    private PdfPageLayout page(int number, String header, String body, String footer) {
        List<PdfTextLine> lines = List.of(
                line(header, 50), line(body, 500), line(footer, 950));
        return new PdfPageLayout(number, 700, 1000,
                String.join("\n", header, body, footer), lines, null, false);
    }

    private PdfPageLayout pageWithBody(int number, String body) {
        return new PdfPageLayout(number, 700, 1000, body,
                List.of(line(body, 500)), null, false);
    }

    private PdfTextLine line(String text, double y) {
        return new PdfTextLine(text, 40, y, 200, 12, 12,
                "Helvetica", false, 700, 1000);
    }

    private List<String> texts(Set<PdfTextLine> lines) {
        return lines.stream().map(PdfTextLine::text).toList();
    }
}
