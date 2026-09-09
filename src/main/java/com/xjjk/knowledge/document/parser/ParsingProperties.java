package com.xjjk.knowledge.document.parser;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.document.parsing")
public class ParsingProperties {
    private int maxPages = 500;
    private int maxSlides = 500;
    private int maxSheets = 100;
    private int maxRowsPerSheet = 100_000;
    private int maxDocxUnits = 100_000;

    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public int getMaxSlides() { return maxSlides; }
    public void setMaxSlides(int maxSlides) { this.maxSlides = maxSlides; }
    public int getMaxSheets() { return maxSheets; }
    public void setMaxSheets(int maxSheets) { this.maxSheets = maxSheets; }
    public int getMaxRowsPerSheet() { return maxRowsPerSheet; }
    public void setMaxRowsPerSheet(int maxRowsPerSheet) { this.maxRowsPerSheet = maxRowsPerSheet; }
    public int getMaxDocxUnits() { return maxDocxUnits; }
    public void setMaxDocxUnits(int maxDocxUnits) { this.maxDocxUnits = maxDocxUnits; }
}
