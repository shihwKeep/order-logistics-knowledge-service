package com.xjjk.knowledge.document.parser;

public interface DocumentParser {
    boolean supports(String extension, String mimeType);

    ParsedDocument parse(ParseRequest request);

    String version();
}
