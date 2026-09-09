package com.xjjk.knowledge.document.parser;

/** 可定位的解析单元，后续原文预览和引用定位都依赖这些字段。 */
public record ParsedUnit(
        String unitType,
        int unitIndex,
        String locationLabel,
        String titlePath,
        String text,
        Double ocrConfidence,
        boolean lowConfidence) {

    public static ParsedUnit text(int index, String location, String titlePath, String text) {
        return new ParsedUnit("TEXT", index, location, titlePath, text, null, false);
    }
}
