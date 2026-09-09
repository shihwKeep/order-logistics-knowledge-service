package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** DOCX 按正文顺序提取标题、段落和表格。 */
@Component
public class DocxDocumentParser implements DocumentParser {
    private final int maxUnits;

    @Autowired
    public DocxDocumentParser(ParsingProperties properties) {
        this(properties.getMaxDocxUnits());
    }

    DocxDocumentParser(int maxUnits) {
        this.maxUnits = maxUnits;
    }

    @Override
    public boolean supports(String extension, String mimeType) {
        return "docx".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(request.content()))) {
            List<ParsedUnit> units = new ArrayList<>();
            String[] headings = new String[6];
            int index = 1;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = TextDecoder.normalizeInline(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    int headingLevel = headingLevel(paragraph.getStyle());
                    if (headingLevel > 0) {
                        headings[headingLevel - 1] = text;
                        Arrays.fill(headings, headingLevel, headings.length, null);
                    } else {
                        requireCapacity(index);
                        units.add(ParsedUnit.text(index, "段落 " + index, titlePath(headings), text));
                        index++;
                    }
                } else if (element instanceof XWPFTable table) {
                    String text = tableText(table);
                    if (!text.isBlank()) {
                        requireCapacity(index);
                        units.add(ParsedUnit.text(index, "表格 " + index, titlePath(headings), text));
                        index++;
                    }
                }
            }
            return new ParsedDocument(units, false);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_PARSE_FAILED, exception);
        }
    }

    @Override
    public String version() {
        return "docx-poi-v1";
    }

    private int headingLevel(String style) {
        if (style == null) {
            return 0;
        }
        String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.startsWith("heading") || normalized.startsWith("标题")) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last >= '1' && last <= '6') {
                return last - '0';
            }
        }
        return 0;
    }

    private String tableText(XWPFTable table) {
        return table.getRows().stream()
                .map(row -> row.getTableCells().stream()
                        .map(cell -> TextDecoder.normalizeInline(cell.getText()))
                        .reduce((left, right) -> left + " | " + right)
                        .orElse(""))
                .filter(row -> !row.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String titlePath(String[] headings) {
        return String.join(" > ", Arrays.stream(headings)
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private void requireCapacity(int index) {
        if (index > maxUnits) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_LIMIT_EXCEEDED);
        }
    }
}
