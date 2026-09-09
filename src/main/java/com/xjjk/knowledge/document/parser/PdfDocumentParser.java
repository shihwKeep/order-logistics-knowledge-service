package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.ocr.OcrClient;
import com.xjjk.knowledge.document.ocr.OcrResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** 按页提取 PDF：优先使用文本层，仅对无文本或低密度页面进行 OCR。 */
@Component
public class PdfDocumentParser implements DocumentParser {
    private static final int MIN_TEXT_CHARACTERS = 12;
    private final OcrClient ocrClient;
    private final int maxPages;

    @Autowired
    public PdfDocumentParser(OcrClient ocrClient, ParsingProperties properties) {
        this(ocrClient, properties.getMaxPages());
    }

    PdfDocumentParser(OcrClient ocrClient, int maxPages) {
        this.ocrClient = ocrClient;
        this.maxPages = maxPages;
    }

    @Override
    public boolean supports(String extension, String mimeType) {
        return "pdf".equals(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        try (PDDocument document = Loader.loadPDF(request.content())) {
            if (document.getNumberOfPages() > maxPages) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_LIMIT_EXCEEDED);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            List<ParsedUnit> units = new ArrayList<>();
            boolean ocrRequired = false;
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                String text = stripper.getText(document).strip();
                Double confidence = null;
                boolean lowConfidence = false;
                if (meaningfulCharacters(text) < MIN_TEXT_CHARACTERS) {
                    OcrResult result = ocrClient.recognize(
                            UUID.randomUUID().toString(), "ch", renderPng(renderer, pageIndex));
                    text = result.joinedText();
                    confidence = result.averageConfidence();
                    lowConfidence = result.hasLowConfidence();
                    ocrRequired = true;
                }
                units.add(new ParsedUnit(
                        "PAGE", pageIndex + 1, "第 " + (pageIndex + 1) + " 页", "",
                        text, confidence, lowConfidence));
            }
            return new ParsedDocument(units, ocrRequired);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_PARSE_FAILED, exception);
        }
    }

    private static int meaningfulCharacters(String text) {
        return text == null ? 0 : text.replaceAll("\\s+", "").length();
    }

    private static byte[] renderPng(PDFRenderer renderer, int pageIndex) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 180);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Override
    public String version() {
        return "pdfbox-3-ocr-v1";
    }
}
