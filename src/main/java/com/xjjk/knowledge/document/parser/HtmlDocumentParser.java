package com.xjjk.knowledge.document.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** HTML 解析器主动移除脚本、样式、导航和表单等页面噪声。 */
@Component
public class HtmlDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "html".equals(extension) || "htm".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        Document document = Jsoup.parse(TextDecoder.decode(request.content()));
        document.select("script,style,noscript,nav,header,footer,form,iframe").remove();
        String[] headings = new String[6];
        List<ParsedUnit> units = new ArrayList<>();
        int index = 1;
        for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,li,table")) {
            String tag = element.normalName();
            if (tag.startsWith("h")) {
                int level = Integer.parseInt(tag.substring(1));
                headings[level - 1] = TextDecoder.normalizeInline(element.text());
                Arrays.fill(headings, level, headings.length, null);
                continue;
            }
            if (element.parents().stream().anyMatch(parent -> parent.normalName().equals("table"))
                    && !tag.equals("table")) {
                continue;
            }
            String text = tag.equals("table") ? tableText(element) : TextDecoder.normalizeInline(element.text());
            if (tag.equals("li")) {
                text = "- " + text;
            }
            if (!text.isBlank()) {
                units.add(ParsedUnit.text(index, "HTML 区块 " + index, titlePath(headings), text));
                index++;
            }
        }
        return new ParsedDocument(units, false);
    }

    @Override
    public String version() {
        return "html-parser-v1";
    }

    private String tableText(Element table) {
        List<String> rows = table.select("tr").stream()
                .map(row -> row.select("th,td").stream()
                        .map(cell -> TextDecoder.normalizeInline(cell.text()))
                        .reduce((left, right) -> left + " | " + right)
                        .orElse(""))
                .filter(row -> !row.isBlank())
                .toList();
        return String.join("\n", rows);
    }

    private String titlePath(String[] headings) {
        return String.join(" > ", Arrays.stream(headings)
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }
}
