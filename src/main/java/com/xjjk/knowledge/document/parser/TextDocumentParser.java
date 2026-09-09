package com.xjjk.knowledge.document.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** TXT 与 Markdown 解析器；Markdown 标题被保存为来源标题路径。 */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "txt".equals(extension) || "md".equals(extension);
    }

    @Override
    public ParsedDocument parse(ParseRequest request) {
        String decoded = TextDecoder.decode(request.content())
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (!"md".equals(request.extension())) {
            return new ParsedDocument(
                    List.of(ParsedUnit.text(1, "全文", null, decoded)), false);
        }
        return parseMarkdown(decoded);
    }

    @Override
    public String version() {
        return "text-parser-v1";
    }

    private ParsedDocument parseMarkdown(String markdown) {
        List<ParsedUnit> units = new ArrayList<>();
        String[] headings = new String[6];
        StringBuilder body = new StringBuilder();
        int index = 1;
        for (String line : markdown.split("\n", -1)) {
            int level = headingLevel(line);
            if (level > 0) {
                if (!body.toString().isBlank()) {
                    units.add(ParsedUnit.text(index++, "区块 " + (index - 1),
                            titlePath(headings), body.toString().strip()));
                    body.setLength(0);
                }
                headings[level - 1] = line.substring(level).trim();
                Arrays.fill(headings, level, headings.length, null);
            } else {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank() || units.isEmpty()) {
            units.add(ParsedUnit.text(index, "区块 " + index,
                    titlePath(headings), body.toString().strip()));
        }
        return new ParsedDocument(units, false);
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && Character.isWhitespace(line.charAt(level))
                ? level : 0;
    }

    private String titlePath(String[] headings) {
        return String.join(" > ", Arrays.stream(headings)
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }
}
