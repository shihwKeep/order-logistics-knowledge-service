package com.xjjk.knowledge.document.parser;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfLayoutExtractorTest {

    @Test
    void extractsOrderedVisualLinesWithFontAndGeometry() throws Exception {
        try (PDDocument document = layoutPdf()) {
            PdfPageLayout page = new PdfLayoutExtractor().extract(document, 0);

            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.rawText()).contains("1 Refund Rules", "Body text");
            assertThat(page.lines()).extracting(PdfTextLine::text)
                    .containsExactly("1 Refund Rules", "Body text");
            assertThat(page.lines().getFirst().fontSize())
                    .isGreaterThan(page.lines().get(1).fontSize());
            assertThat(page.lines().getFirst().bold()).isTrue();
            assertThat(page.lines().getFirst().yRatio()).isBetween(0D, 1D);
        }
    }

    private PDDocument layoutPdf() throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.newLineAtOffset(72, 700);
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
            stream.showText("1 Refund");
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
            stream.showText(" Rules");
            stream.endText();

            stream.beginText();
            stream.newLineAtOffset(72, 660);
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            stream.showText("Body text");
            stream.endText();
        }
        return document;
    }
}
