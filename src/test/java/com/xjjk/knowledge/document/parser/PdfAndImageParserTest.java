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

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
