package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** XLS/XLSX 按工作表和数据行解析，每行重复携带表头。 */
@Component
public class SpreadsheetDocumentParser implements DocumentParser {
    private final int maxSheets;
    private final int maxRowsPerSheet;

    public SpreadsheetDocumentParser() {
        this(100, 100_000);
    }

    SpreadsheetDocumentParser(int maxSheets, int maxRowsPerSheet) {
        this.maxSheets = maxSheets;
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    @Override
    public boolean supports(String extension, String mimeType) {
        return "xls".equals(extension) || "xlsx".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(request.content()))) {
            if (workbook.getNumberOfSheets() > maxSheets) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_LIMIT_EXCEEDED);
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ParsedUnit> units = new ArrayList<>();
            int unitIndex = 1;
            for (Sheet sheet : workbook) {
                if (sheet.getLastRowNum() + 1 > maxRowsPerSheet) {
                    throw new BusinessException(ApiErrorCode.DOCUMENT_LIMIT_EXCEEDED);
                }
                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                if (headerRow == null) {
                    continue;
                }
                String header = rowText(headerRow, formatter, evaluator);
                for (int rowIndex = sheet.getFirstRowNum() + 1;
                     rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    String body = rowText(row, formatter, evaluator);
                    if (body.isBlank()) {
                        continue;
                    }
                    units.add(ParsedUnit.text(
                            unitIndex++,
                            "工作表 " + sheet.getSheetName() + " 第 " + (rowIndex + 1) + " 行",
                            sheet.getSheetName(),
                            header + "\n" + body));
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
        return "spreadsheet-poi-v1";
    }

    private String rowText(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>();
        int lastCell = Math.max(row.getLastCellNum(), 0);
        for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
            values.add(TextDecoder.normalizeInline(formatter.formatCellValue(
                    row.getCell(cellIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK), evaluator)));
        }
        return String.join(" | ", values);
    }
}
