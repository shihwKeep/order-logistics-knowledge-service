package com.xjjk.knowledge.document.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CsvDocumentParserTest {

    @Test
    void preservesHeaderAndQuotedMultilineCells() {
        String csv = "编号,说明\nA01,普通退款\nA02,\"物流异常\n需要人工处理\"";

        ParsedDocument parsed = new CsvDocumentParser().parse(new ParseRequest(
                "rules.csv", "csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(parsed.units()).hasSize(2);
        assertThat(parsed.units().get(0).text()).isEqualTo("编号 | 说明\nA01 | 普通退款");
        assertThat(parsed.units().get(1).text())
                .isEqualTo("编号 | 说明\nA02 | 物流异常 需要人工处理");
    }
}
