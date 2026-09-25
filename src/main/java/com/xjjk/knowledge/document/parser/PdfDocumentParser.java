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
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** 版面感知解析 PDF：优先使用文本层，仅对无文本或低密度页面进行 OCR。 */
@Component
public class PdfDocumentParser implements DocumentParser {
    private static final int MIN_TEXT_CHARACTERS = 12;
    private final OcrClient ocrClient;
    private final int maxPages;
    private final PdfLayoutExtractor layoutExtractor;
    private final PdfStructureAnalyzer structureAnalyzer;

    @Autowired
    public PdfDocumentParser(
            OcrClient ocrClient,
            ParsingProperties properties,
            PdfLayoutExtractor layoutExtractor,
            PdfStructureAnalyzer structureAnalyzer) {
        this(ocrClient, properties.getMaxPages(), layoutExtractor, structureAnalyzer);
    }

    PdfDocumentParser(OcrClient ocrClient, int maxPages) {
        PdfRepeatedArtifactDetector artifactDetector = new PdfRepeatedArtifactDetector();
        this.ocrClient = ocrClient;
        this.maxPages = maxPages;
        this.layoutExtractor = new PdfLayoutExtractor();
        this.structureAnalyzer = new PdfStructureAnalyzer(artifactDetector);
    }

    private PdfDocumentParser(
            OcrClient ocrClient,
            int maxPages,
            PdfLayoutExtractor layoutExtractor,
            PdfStructureAnalyzer structureAnalyzer) {
        this.ocrClient = ocrClient;
        this.maxPages = maxPages;
        this.layoutExtractor = layoutExtractor;
        this.structureAnalyzer = structureAnalyzer;
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
            PDFRenderer renderer = new PDFRenderer(document);
            List<PdfPageLayout> pages = new ArrayList<>();
            boolean ocrRequired = false;
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                PdfPageLayout page = layoutExtractor.extract(document, pageIndex);
                if (meaningfulCharacters(page.rawText()) < MIN_TEXT_CHARACTERS) {
                    RenderedPage rendered = renderPng(renderer, pageIndex);
                    OcrResult result = ocrClient.recognize(
                            UUID.randomUUID().toString(), "ch", rendered.png());
                    page = layoutExtractor.fromOcr(
                            pageIndex + 1,
                            document.getPage(pageIndex).getCropBox().getWidth(),
                            document.getPage(pageIndex).getCropBox().getHeight(),
                            rendered.width(), rendered.height(), result);
                    ocrRequired = true;
                }
                pages.add(page);
            }
            return new ParsedDocument(structureAnalyzer.analyze(request.filename(), pages), ocrRequired);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_PARSE_FAILED, exception);
        }
    }

    private static int meaningfulCharacters(String text) {
        return text == null ? 0 : text.replaceAll("\\s+", "").length();
    }

    private static RenderedPage renderPng(PDFRenderer renderer, int pageIndex) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 180);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new RenderedPage(output.toByteArray(), image.getWidth(), image.getHeight());
    }

    @Override
    public String version() {
        return "pdfbox-3-ocr-v2";
    }

    private record RenderedPage(byte[] png, int width, int height) {
    }
}
