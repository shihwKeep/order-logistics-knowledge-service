package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** PPTX 按幻灯片提取正文、表格与备注，并标记需要 OCR 的图片区域。 */
@Component
public class PptxDocumentParser implements DocumentParser {
    private final int maxSlides;

    @Autowired
    public PptxDocumentParser(ParsingProperties properties) {
        this(properties.getMaxSlides());
    }

    PptxDocumentParser(int maxSlides) {
        this.maxSlides = maxSlides;
    }

    @Override
    public boolean supports(String extension, String mimeType) {
        return "pptx".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(request.content()))) {
            if (show.getSlides().size() > maxSlides) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_LIMIT_EXCEEDED);
            }
            List<ParsedUnit> units = new ArrayList<>();
            boolean ocrRequired = false;
            for (int slideIndex = 0; slideIndex < show.getSlides().size(); slideIndex++) {
                var slide = show.getSlides().get(slideIndex);
                List<String> blocks = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFPictureShape) {
                        ocrRequired = true;
                    } else if (shape instanceof XSLFTable table) {
                        String tableText = table.getRows().stream()
                                .map(row -> row.getCells().stream()
                                        .map(cell -> TextDecoder.normalizeInline(cell.getText()))
                                        .reduce((left, right) -> left + " | " + right)
                                        .orElse(""))
                                .filter(row -> !row.isBlank())
                                .reduce((left, right) -> left + "\n" + right)
                                .orElse("");
                        if (!tableText.isBlank()) {
                            blocks.add(tableText);
                        }
                    } else if (shape instanceof XSLFTextShape textShape) {
                        String text = TextDecoder.normalizeInline(textShape.getText());
                        if (!text.isBlank()) {
                            blocks.add(text);
                        }
                    }
                }
                if (slide.getNotes() != null) {
                    slide.getNotes().getShapes().stream()
                            .filter(XSLFTextShape.class::isInstance)
                            .map(XSLFTextShape.class::cast)
                            .map(shape -> TextDecoder.normalizeInline(shape.getText()))
                            .filter(text -> !text.isBlank())
                            .forEach(text -> blocks.add("备注：" + text));
                }
                if (!blocks.isEmpty()) {
                    units.add(new ParsedUnit(
                            "SLIDE",
                            slideIndex + 1,
                            "幻灯片 " + (slideIndex + 1),
                            blocks.getFirst(),
                            String.join("\n", blocks),
                            null,
                            false));
                }
            }
            return new ParsedDocument(units, ocrRequired);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_PARSE_FAILED, exception);
        }
    }

    @Override
    public String version() {
        return "pptx-poi-v1";
    }
}
