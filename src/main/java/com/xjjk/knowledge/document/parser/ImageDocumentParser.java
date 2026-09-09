package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.document.ocr.OcrClient;
import com.xjjk.knowledge.document.ocr.OcrResult;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** PNG/JPEG 本身没有文本层，必须整图 OCR。 */
@Component
public class ImageDocumentParser implements DocumentParser {
    private final OcrClient ocrClient;

    public ImageDocumentParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    @Override
    public boolean supports(String extension, String mimeType) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return normalized.equals("png") || normalized.equals("jpg") || normalized.equals("jpeg");
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        OcrResult result = ocrClient.recognize(UUID.randomUUID().toString(), "ch", request.content());
        ParsedUnit unit = new ParsedUnit(
                "IMAGE", 1, "图片", "", result.joinedText(),
                result.averageConfidence(), result.hasLowConfidence());
        return new ParsedDocument(List.of(unit), true);
    }

    @Override
    public String version() {
        return "image-ocr-v1";
    }
}
