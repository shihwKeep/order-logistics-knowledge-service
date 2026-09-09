package com.xjjk.knowledge.document.parser;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** CSV 每一数据行形成可定位单元，并重复携带表头以保留字段语义。 */
@Component
public class CsvDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "csv".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();
        try (CSVParser parser = format.parse(new StringReader(TextDecoder.decode(request.content())))) {
            String header = String.join(" | ", parser.getHeaderNames());
            List<ParsedUnit> units = new ArrayList<>();
            int index = 1;
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>();
                record.forEach(cell -> cells.add(TextDecoder.normalizeInline(cell)));
                units.add(ParsedUnit.text(
                        index,
                        "数据行 " + record.getRecordNumber(),
                        request.filename(),
                        header + "\n" + String.join(" | ", cells)));
                index++;
            }
            return new ParsedDocument(units, false);
        } catch (IOException exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_PARSE_FAILED, exception);
        }
    }

    @Override
    public String version() {
        return "csv-parser-v1";
    }
}
