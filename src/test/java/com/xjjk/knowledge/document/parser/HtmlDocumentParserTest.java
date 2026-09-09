package com.xjjk.knowledge.document.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlDocumentParserTest {

    @Test
    void removesExecutableAndNavigationNoiseButKeepsStructure() {
        String html = """
                <html><head><style>.x{}</style><script>steal()</script></head>
                <body><nav>菜单</nav><h1>退款规则</h1><p>签收后七日内可以申请。</p>
                <h2>例外</h2><ul><li>定制商品除外</li></ul></body></html>
                """;

        ParsedDocument parsed = new HtmlDocumentParser().parse(new ParseRequest(
                "rule.html", "html", "text/html", html.getBytes(StandardCharsets.UTF_8)));

        assertThat(parsed.units()).extracting(ParsedUnit::text)
                .containsExactly("签收后七日内可以申请。", "- 定制商品除外");
        assertThat(parsed.units()).extracting(ParsedUnit::titlePath)
                .containsExactly("退款规则", "退款规则 > 例外");
        assertThat(parsed.units()).extracting(ParsedUnit::text)
                .allMatch(text -> !text.contains("steal") && !text.contains("菜单"));
    }
}
