package com.xjjk.knowledge.document.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.xjjk.knowledge.document.ocr.OcrBlock;
import com.xjjk.knowledge.document.ocr.OcrClient;
import com.xjjk.knowledge.document.ocr.OcrResult;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class PdfAndImageParserTest {

    @Test
    void extractsTextPdfWithoutCallingOcr() throws Exception {
        AtomicInteger ocrCalls = new AtomicInteger();
        OcrClient ocr = (requestId, language, image) -> {
            ocrCalls.incrementAndGet();
            return recognized("不应调用");
        };
        PdfDocumentParser parser = new PdfDocumentParser(ocr, 3);

        ParsedDocument parsed = parser.parse(new ParseRequest(
                "policy.pdf", "pdf", "application/pdf", textPdf("Refund policy applies within seven days.")));

        assertThat(parsed.units()).singleElement().satisfies(unit -> {
            assertThat(unit.locationLabel()).isEqualTo("第 1 页");
            assertThat(unit.text()).contains("Refund policy");
            assertThat(unit.ocrConfidence()).isNull();
        });
        assertThat(ocrCalls).hasValue(0);
    }

    @Test
    void removesRepeatedMarginsAndProducesSectionTitlePaths() throws Exception {
        OcrClient ocr = (requestId, language, image) -> {
            throw new AssertionError("文本型 PDF 不应调用 OCR");
        };
        PdfDocumentParser parser = new PdfDocumentParser(ocr, 10);

        ParsedDocument parsed = parser.parse(new ParseRequest(
                "refund-policy.pdf", "pdf", "application/pdf", structuredPdf()));

        assertThat(parsed.units()).extracting(ParsedUnit::titlePath)
                .contains("refund-policy > 1 Refund Eligibility")
                .contains("refund-policy > 1 Refund Eligibility > 1.1 Applicant");
        assertThat(parsed.units()).allSatisfy(unit ->
                assertThat(unit.text()).doesNotContain("Internal Material", "Page 1", "Page 2", "Page 3"));
        assertThat(parsed.units()).anySatisfy(unit ->
                assertThat(unit.rawText()).contains("Internal Material Page 2"));
        assertThat(parsed.units())
                .filteredOn(unit -> unit.text().contains("1. Verify order owner"))
                .singleElement()
                .satisfies(unit -> assertThat(unit.titlePath()).contains("1.1 Applicant"));
    }

    @Test
    void rendersScannedPdfAndCallsOcrForEachRequiredPage() throws Exception {
        AtomicInteger ocrCalls = new AtomicInteger();
        OcrClient ocr = (requestId, language, image) -> {
            ocrCalls.incrementAndGet();
            assertThat(image).isNotEmpty();
            return recognized("扫描件退款规则");
        };
        PdfDocumentParser parser = new PdfDocumentParser(ocr, 3);

        ParsedDocument parsed = parser.parse(new ParseRequest(
                "scan.pdf", "pdf", "application/pdf", imagePdf()));

        assertThat(parsed.ocrRequired()).isTrue();
        assertThat(parsed.units()).singleElement().satisfies(unit -> {
            assertThat(unit.text()).isEqualTo("扫描件退款规则");
            assertThat(unit.titlePath()).isEqualTo("scan");
            assertThat(unit.ocrConfidence()).isEqualTo(0.91);
        });
        assertThat(ocrCalls).hasValue(1);
    }

    @Test
    void sendsImageDirectlyToOcrAndPreservesLowConfidenceFlag() throws Exception {
        OcrClient ocr = (requestId, language, image) -> new OcrResult(
                requestId,
                0,
                List.of(new OcrBlock("模糊文字", 0.51, List.of(), true)));
        ImageDocumentParser parser = new ImageDocumentParser(ocr);

        ParsedDocument parsed = parser.parse(new ParseRequest(
                "photo.png", "png", "image/png", pngBytes()));

        assertThat(parsed.ocrRequired()).isTrue();
        assertThat(parsed.units()).singleElement().satisfies(unit -> {
            assertThat(unit.text()).isEqualTo("模糊文字");
            assertThat(unit.lowConfidence()).isTrue();
        });
    }

    private static OcrResult recognized(String text) {
        return new OcrResult("request", 0, List.of(new OcrBlock(text, 0.91, List.of(), false)));
    }

    private static byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] imagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] structuredPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addStructuredPage(document, 1, List.of(
                    new TextRun("1 Refund Eligibility", 18, true, 680),
                    new TextRun("The request must be filed within the valid period.", 10, false, 650),
                    new TextRun("1.1 Applicant", 14, true, 600),
                    new TextRun("The applicant must own the order.", 10, false, 570)));
            addStructuredPage(document, 2, List.of(
                    new TextRun("1. Verify order owner", 10, false, 680),
                    new TextRun("Continue checking duplicate requests.", 10, false, 650),
                    new TextRun("1.2 Evidence", 14, true, 590),
                    new TextRun("The user must provide a valid receipt.", 10, false, 560)));
            addStructuredPage(document, 3, List.of(
                    new TextRun("The evidence must match the order.", 10, false, 680)));
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addStructuredPage(
            PDDocument document, int pageNumber, List<TextRun> runs) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            write(stream, "Internal Material Page " + pageNumber, 9, false, 760);
            for (TextRun run : runs) {
                write(stream, run.text(), run.fontSize(), run.bold(), run.y());
            }
            write(stream, "Page " + pageNumber, 9, false, 40);
        }
    }

    private static void write(
            PDPageContentStream stream, String text, float fontSize, boolean bold, float y) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(bold
                ? Standard14Fonts.FontName.HELVETICA_BOLD
                : Standard14Fonts.FontName.HELVETICA), fontSize);
        stream.newLineAtOffset(72, y);
        stream.showText(text);
        stream.endText();
    }

    private record TextRun(String text, float fontSize, boolean bold, float y) {
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
