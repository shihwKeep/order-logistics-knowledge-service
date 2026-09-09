package com.xjjk.knowledge.document.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    void parsesUtf8MarkdownWithHeadingPath() {
        ParsedDocument parsed = parser.parse(new ParseRequest(
                "规则.md", "md", "text/markdown",
                "# 售后规则\n\n签收后七日内可以申请。".getBytes(StandardCharsets.UTF_8)));

        assertThat(parsed.units()).hasSize(1);
        assertThat(parsed.units().getFirst().titlePath()).isEqualTo("售后规则");
        assertThat(parsed.units().getFirst().text()).contains("签收后七日内");
    }

    @Test
    void fallsBackToGb18030ForLegacyText() {
        byte[] bytes = "物流异常处理规范".getBytes(Charset.forName("GB18030"));

        ParsedDocument parsed = parser.parse(new ParseRequest(
                "物流.txt", "txt", "text/plain", bytes));

        assertThat(parsed.units().getFirst().text()).isEqualTo("物流异常处理规范");
    }
}
