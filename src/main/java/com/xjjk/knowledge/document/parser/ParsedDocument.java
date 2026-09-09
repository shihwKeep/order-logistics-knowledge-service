package com.xjjk.knowledge.document.parser;

import java.util.List;

public record ParsedDocument(List<ParsedUnit> units, boolean ocrRequired) {
    public ParsedDocument {
        units = List.copyOf(units);
    }
}
