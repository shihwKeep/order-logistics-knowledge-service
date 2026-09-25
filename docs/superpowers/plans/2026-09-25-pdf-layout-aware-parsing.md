# PDF Layout-Aware Parsing Implementation Plan

> **Execution:** Use the `executing-plans` workflow in this conversation and complete the checklist task by task. Per the project owner's instruction, do not delegate implementation to subagents.

**Goal:** Upgrade PDF ingestion from page-level plain-text extraction to layout-aware header/footer cleanup and section-level title-path extraction while preserving auditable raw text.

**Architecture:** PDFBox `TextPosition` data is converted into immutable page/line layout models. A repeated-artifact detector removes only cross-page header/footer candidates from effective text, and a structure analyzer combines font hierarchy, geometry, whitespace, line length, and numbering evidence to create section units with stable title paths. Existing ingestion persistence, chunking, Outbox, Elasticsearch, and Milvus flows remain the only write path.

**Tech Stack:** Java 21, Spring Boot 3.5, Apache PDFBox 3.0.8, JUnit 5, AssertJ, MySQL, Elasticsearch, Milvus.

**Execution constraint:** Per the project owner's standing instruction, execute inline in the current local `main` checkout and commit each task locally; do not delegate to subagents or leave changes in a separate worktree.

**Code-comment constraint:** New production classes and non-obvious algorithms must include concise Chinese comments explaining the decision boundary (line grouping, artifact thresholds, heading evidence, OCR degradation, and path truncation), not line-by-line restatements.

---

## File Structure

**Create**

- `src/main/java/com/xjjk/knowledge/document/parser/PdfTextLine.java` — immutable visual-line facts from PDF text or OCR.
- `src/main/java/com/xjjk/knowledge/document/parser/PdfPageLayout.java` — one page's geometry, raw text, visual lines, and OCR quality.
- `src/main/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractor.java` — PDFBox `TextPosition` collection and visual-line construction.
- `src/main/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetector.java` — cross-page repeated header/footer and page-number detection.
- `src/main/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzer.java` — heading scoring, hierarchy, cross-page title stack, and section-unit creation.
- `src/test/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractorTest.java` — layout extraction contract.
- `src/test/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetectorTest.java` — artifact-removal contract.
- `src/test/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzerTest.java` — title hierarchy and section splitting contract.

**Modify**

- `src/main/java/com/xjjk/knowledge/document/parser/ParsedUnit.java` — represent raw and effective text without breaking existing parsers.
- `src/main/java/com/xjjk/knowledge/document/parser/PdfDocumentParser.java` — orchestrate text-layer extraction, OCR fallback, cleanup, and structure analysis.
- `src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactRepository.java` — persist `rawText` and normalized effective `text` separately.
- `src/test/java/com/xjjk/knowledge/document/parser/PdfAndImageParserTest.java` — end-to-end PDF parser regression and version checks.
- `src/test/java/com/xjjk/knowledge/document/task/IngestionArtifactRepositoryTest.java` — new focused unit test for raw/effective persistence.

No database migration is required because `kb_document_unit.raw_text`, `effective_text`, `title_path`, and `location_label` already exist.

---

### Task 1: Separate Raw and Effective Parsed Text

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/document/parser/ParsedUnit.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactRepository.java`
- Test: `src/test/java/com/xjjk/knowledge/document/task/IngestionArtifactRepositoryTest.java`

- [ ] **Step 1: Write a failing repository test**

Use a mocked `IngestionArtifactMapper`, a real `TextNormalizer`, and an `ArgumentCaptor<DocumentUnitEntity>`. Construct a parsed unit whose raw text contains a repeated header while effective text does not:

```java
ParsedUnit unit = new ParsedUnit(
        "PDF_SECTION", 1, "第 2 页", "退款规范 > 退款资格",
        "退款资格正文", null, false,
        "退款规范\n第 2 页\n退款资格正文");

repository.replaceParsedArtifacts(
        version, new ParsedDocument(List.of(unit), false), List.of(), "pdfbox-3-ocr-v2");

verify(mapper).insertUnit(unitCaptor.capture());
DocumentUnitEntity saved = unitCaptor.getValue();
assertThat(saved.getRawText()).isEqualTo("退款规范\n第 2 页\n退款资格正文");
assertThat(saved.getEffectiveText()).isEqualTo("退款资格正文");
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -Dtest=IngestionArtifactRepositoryTest test
```

Expected: compilation fails because `ParsedUnit` has no independent raw-text component, or the assertion shows both columns contain the same value.

- [ ] **Step 3: Add the backward-compatible raw-text component**

Change `ParsedUnit` to:

```java
public record ParsedUnit(
        String unitType,
        int unitIndex,
        String locationLabel,
        String titlePath,
        String text,
        Double ocrConfidence,
        boolean lowConfidence,
        String rawText) {

    public ParsedUnit(
            String unitType,
            int unitIndex,
            String locationLabel,
            String titlePath,
            String text,
            Double ocrConfidence,
            boolean lowConfidence) {
        this(unitType, unitIndex, locationLabel, titlePath, text,
                ocrConfidence, lowConfidence, text);
    }

    public static ParsedUnit text(int index, String location, String titlePath, String text) {
        return new ParsedUnit("TEXT", index, location, titlePath, text, null, false, text);
    }
}
```

In `IngestionArtifactRepository.toEntity`, persist the two channels separately:

```java
entity.setRawText(unit.rawText());
entity.setEffectiveText(normalizer.normalize(unit.text()));
```

The seven-argument constructor keeps Markdown, HTML, DOCX, XLSX, PPTX, image, correction, and existing test call sites source-compatible.

- [ ] **Step 4: Run focused and parser regression tests**

Run:

```powershell
mvn -Dtest=IngestionArtifactRepositoryTest,TextDocumentParserTest,HtmlDocumentParserTest,OfficeDocumentParserTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/xjjk/knowledge/document/parser/ParsedUnit.java `
        src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactRepository.java `
        src/test/java/com/xjjk/knowledge/document/task/IngestionArtifactRepositoryTest.java
git commit -m "refactor: separate raw and effective parsed text"
```

---

### Task 2: Extract PDF Visual Lines

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfTextLine.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfPageLayout.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractor.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractorTest.java`

- [ ] **Step 1: Write a failing extraction test**

Generate a page with a 16pt heading and 10pt body. Assert page number, raw text, order, font size, and coordinates:

```java
@Test
void extractsOrderedVisualLinesWithFontAndGeometry() throws Exception {
    try (PDDocument document = layoutPdf()) {
        PdfPageLayout page = new PdfLayoutExtractor().extract(document, 0);

        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.rawText()).contains("1 Refund Rules", "Body text");
        assertThat(page.lines()).extracting(PdfTextLine::text)
                .containsExactly("1 Refund Rules", "Body text");
        assertThat(page.lines().getFirst().fontSize())
                .isGreaterThan(page.lines().get(1).fontSize());
        assertThat(page.lines().getFirst().yRatio()).isBetween(0D, 1D);
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -Dtest=PdfLayoutExtractorTest test
```

Expected: compilation fails because the layout types do not exist.

- [ ] **Step 3: Add immutable layout records**

Create `PdfTextLine`:

```java
record PdfTextLine(
        String text,
        double x,
        double y,
        double width,
        double height,
        double fontSize,
        String fontName,
        boolean bold,
        double pageWidth,
        double pageHeight) {

    double yRatio() {
        return pageHeight <= 0D ? 0D : y / pageHeight;
    }
}
```

Create `PdfPageLayout`:

```java
record PdfPageLayout(
        int pageNumber,
        double width,
        double height,
        String rawText,
        List<PdfTextLine> lines,
        Double ocrConfidence,
        boolean lowConfidence) {

    PdfPageLayout {
        lines = List.copyOf(lines);
    }
}
```

- [ ] **Step 4: Implement PDFBox line collection**

Implement `PdfLayoutExtractor.extract(PDDocument, int)` with a private `PDFTextStripper` subclass. Set `sortByPosition=true`, collect every `TextPosition`, then group glyphs into visual lines by writing direction and a Y tolerance derived from glyph height. Sort lines top-to-bottom and glyphs left-to-right; infer a space when the X gap is materially larger than the glyph's normal word/character spacing. Do not assume one `writeString` callback equals one visual line because PDFBox may split a rendered line into multiple callbacks.

Declare `PdfLayoutExtractor` as a Spring component when it is created so the parser can receive it through constructor injection in Task 5.

For each grouped line compute:

```java
private PdfTextLine line(String text, List<TextPosition> positions, PDPage page) {
    TextPosition first = positions.getFirst();
    double minX = positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0D);
    double maxX = positions.stream()
            .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
            .max().orElse(minX);
    double minY = positions.stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0D);
    double maxHeight = positions.stream().mapToDouble(TextPosition::getHeightDir).max().orElse(0D);
    double fontSize = positions.stream().mapToDouble(TextPosition::getFontSizeInPt).max().orElse(0D);
    String fontName = first.getFont() == null ? "" : first.getFont().getName();
    boolean bold = fontName.toLowerCase(Locale.ROOT).contains("bold")
            || fontName.toLowerCase(Locale.ROOT).contains("black");
    double pageWidth = page.getCropBox().getWidth();
    double pageHeight = page.getCropBox().getHeight();
    return new PdfTextLine(text.strip(), minX, minY, maxX - minX, maxHeight,
            fontSize, fontName, bold, pageWidth, pageHeight);
}
```

Ignore blank lines, preserve reading order, and build `rawText` by joining the reconstructed visual lines. Keep an end-to-end assertion for split text runs so two font spans on the same Y coordinate remain one logical line.

- [ ] **Step 5: Verify GREEN and commit**

Run:

```powershell
mvn -Dtest=PdfLayoutExtractorTest test
```

Expected: PASS.

```powershell
git add src/main/java/com/xjjk/knowledge/document/parser/PdfTextLine.java `
        src/main/java/com/xjjk/knowledge/document/parser/PdfPageLayout.java `
        src/main/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractor.java `
        src/test/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractorTest.java
git commit -m "feat: extract PDF visual line metadata"
```

---

### Task 3: Detect Repeated Headers, Footers, and Page Numbers

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetector.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetectorTest.java`

- [ ] **Step 1: Write failing detector tests**

Cover three separate behaviors:

```java
@Test
void removesOnlyRepeatedTopAndBottomLines() {
    Set<PdfTextLine> artifacts = detector.detect(List.of(
            page(1, "Policy Manual", "Unique cover title", "Page 1"),
            page(2, "Policy Manual", "Refund body", "Page 2"),
            page(3, "Policy Manual", "Logistics body", "Page 3")));

    assertThat(texts(artifacts)).contains("Policy Manual", "Page 1", "Page 2", "Page 3");
    assertThat(texts(artifacts)).doesNotContain("Unique cover title", "Refund body", "Logistics body");
}

@Test
void doesNotDeleteRepeatedBodyTextOutsideMarginBands() {
    assertThat(detector.detect(pagesWithRepeatedBodySentence())).isEmpty();
}

@Test
void leavesTwoPageDocumentConservative() {
    assertThat(detector.detect(twoPagesWithSameTopText())).isEmpty();
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -Dtest=PdfRepeatedArtifactDetectorTest test
```

Expected: compilation fails because the detector is missing.

- [ ] **Step 3: Implement conservative repetition detection**

Use these explicit rules:

```java
private static final double TOP_BAND = 0.12D;
private static final double BOTTOM_BAND = 0.88D;
private static final double REQUIRED_PAGE_RATIO = 0.60D;
private static final Pattern PAGE_NUMBER = Pattern.compile(
        "^(?:第\\s*)?\\d+(?:\\s*页)?$|^page\\s+\\d+$", Pattern.CASE_INSENSITIVE);

private String normalizedKey(PdfTextLine line) {
    String text = line.text().toLowerCase(Locale.ROOT)
            .replaceAll("第\\s*\\d+\\s*页", "第#页")
            .replaceAll("page\\s+\\d+", "page #")
            .replaceAll("\\b\\d+\\b", "#")
            .replaceAll("\\s+", " ")
            .strip();
    String band = line.yRatio() <= TOP_BAND ? "TOP:" : "BOTTOM:";
    return band + text;
}
```

Only inspect lines inside the top or bottom bands. Require at least three pages. Mark a normalized key repeated when it appears on at least `ceil(pageCount * 0.60)` distinct pages. Treat a pure page number inside a margin band as an artifact even when its normalized text differs.

Declare `PdfRepeatedArtifactDetector` as a Spring component when it is created.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
mvn -Dtest=PdfRepeatedArtifactDetectorTest test
```

Expected: PASS.

```powershell
git add src/main/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetector.java `
        src/test/java/com/xjjk/knowledge/document/parser/PdfRepeatedArtifactDetectorTest.java
git commit -m "feat: remove repeated PDF margin artifacts"
```

---

### Task 4: Build Section Units and Title Paths

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzer.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzerTest.java`

- [ ] **Step 1: Write failing hierarchy tests**

Create deterministic in-memory lines rather than generated PDFs. Cover title scoring, numbered-list rejection, multiple sections on one page, and cross-page inheritance:

```java
@Test
void createsMultipleSectionsAndCarriesHeadingAcrossPages() {
    List<ParsedUnit> units = analyzer.analyze("退款规范.pdf", List.of(
            layout(1,
                    line("1 退款资格", 18, true, 0.20),
                    line("用户应在有效期限内申请。", 10, false, 0.28),
                    line("1.1 申请主体", 14, true, 0.42),
                    line("申请人必须是订单所有者。", 10, false, 0.49)),
            layout(2,
                    line("还应校验是否存在重复申请。", 10, false, 0.20))));

    assertThat(units).extracting(ParsedUnit::titlePath).containsExactly(
            "退款规范 > 1 退款资格",
            "退款规范 > 1 退款资格 > 1.1 申请主体",
            "退款规范 > 1 退款资格 > 1.1 申请主体");
    assertThat(units).extracting(ParsedUnit::locationLabel)
            .containsExactly("第 1 页", "第 1 页", "第 2 页");
}

@Test
void doesNotTreatNumberedBodyListAsHeading() {
    List<ParsedUnit> units = analyzer.analyze("退款规范.pdf", List.of(layout(1,
            line("退款资格", 18, true, 0.20),
            line("1. 校验订单所有者", 10, false, 0.30),
            line("2. 校验重复申请", 10, false, 0.34))));

    assertThat(units).singleElement()
            .extracting(ParsedUnit::titlePath)
            .isEqualTo("退款规范 > 退款资格");
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -Dtest=PdfStructureAnalyzerTest test
```

Expected: compilation fails because the analyzer is missing.

- [ ] **Step 3: Implement evidence-based heading classification**

Calculate the page/document body-font baseline as the median font size of nonblank lines. A line becomes a heading only when it is short and receives at least two independent evidence points:

```java
private boolean isHeading(PdfTextLine line, double bodyFont, double gapBefore, double gapAfter) {
    String text = line.text().strip();
    if (text.isEmpty() || text.length() > 100 || endsLikeSentence(text)) {
        return false;
    }
    int score = 0;
    if (line.fontSize() >= bodyFont * 1.18D) score += 2;
    if (line.bold()) score += 1;
    if (gapBefore >= bodyFont * 0.80D || gapAfter >= bodyFont * 0.80D) score += 1;
    if (HEADING_NUMBER.matcher(text).matches()) score += 1;
    if (line.width() <= line.pageWidth() * 0.75D) score += 1;
    return score >= 3;
}
```

Use numbering depth only to refine the level:

```java
private int headingLevel(String text, double fontSize, List<Double> headingFontLevels) {
    Matcher decimal = DECIMAL_HEADING.matcher(text);
    if (decimal.matches()) {
        return Math.min(4, decimal.group(1).split("\\.").length);
    }
    if (CHAPTER_HEADING.matcher(text).matches()) {
        return 1;
    }
    int fontRank = headingFontLevels.indexOf(fontSize);
    return Math.min(4, fontRank < 0 ? 1 : fontRank + 1);
}
```

Do not use numbering alone: numbered lines at body font size with body spacing remain body text.

Add a deterministic `limitTitlePath` rule for the existing `VARCHAR(1000)` boundary: join the complete path first; while it exceeds 1000 characters and has more than two segments, remove the oldest intermediate segment while preserving the document root and nearest current heading. If root plus leaf alone still exceeds the limit, shorten the root first and always preserve the leaf suffix. Cover this rule with a focused unit test.

Declare `PdfStructureAnalyzer` as a Spring component when it is created.

- [ ] **Step 4: Implement section construction and raw/effective allocation**

Maintain a four-slot heading stack. Use the filename without the final extension as the stable root. A page boundary always flushes the current fragment so every unit keeps an exact page location; the heading stack itself survives the boundary, so body text on the next page inherits the active path. Within a page, every detected heading flushes the previous fragment, updates the stack, and starts another fragment. This yields multiple units on one page while retaining page-precise audit and citations.

For every source line, append its original text to the current page fragment's raw buffer. For lines classified as repeated artifacts, append only to raw text. For retained lines, append to both raw and effective buffers. A leading repeated header encountered before the first business line is held in that page's pending raw buffer and attached to the first page fragment; a trailing repeated footer is attached to the final page fragment. This preserves the ordered union of each page's source text without sending artifacts into chunking.

Construct units with:

```java
units.add(new ParsedUnit(
        "PDF_SECTION",
        nextUnitIndex++,
        "第 " + page.pageNumber() + " 页",
        String.join(" > ", activePath),
        effective.toString().strip(),
        page.ocrConfidence(),
        page.lowConfidence(),
        raw.toString().strip()));
```

If cleaning leaves a nonblank raw section with blank effective text, use the non-artifact body fallback collected before heading classification. If that is also blank, do not create a searchable unit.

- [ ] **Step 5: Verify GREEN and commit**

```powershell
mvn -Dtest=PdfStructureAnalyzerTest test
```

Expected: PASS.

```powershell
git add src/main/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzer.java `
        src/test/java/com/xjjk/knowledge/document/parser/PdfStructureAnalyzerTest.java
git commit -m "feat: derive PDF section title paths"
```

---

### Task 5: Integrate Text PDFs and OCR Fallback

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/document/parser/PdfDocumentParser.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractor.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/parser/PdfAndImageParserTest.java`

- [ ] **Step 1: Write failing end-to-end parser tests**

Extend `PdfAndImageParserTest` with a generated three-page PDF containing a cover, repeated header/footer, two heading levels, a numbered body list, and a second section on the same page:

```java
@Test
void removesRepeatedMarginsAndProducesSectionTitlePaths() throws Exception {
    PdfDocumentParser parser = new PdfDocumentParser(ocrThatMustNotRun(), 10);

    ParsedDocument parsed = parser.parse(new ParseRequest(
            "退款审核规范.pdf", "pdf", "application/pdf", structuredPdf()));

    assertThat(parsed.units()).extracting(ParsedUnit::titlePath)
            .contains("退款审核规范 > 1 退款资格")
            .contains("退款审核规范 > 1 退款资格 > 1.1 申请主体");
    assertThat(parsed.units()).allSatisfy(unit ->
            assertThat(unit.text()).doesNotContain("内部资料 第"));
    assertThat(parsed.units()).anySatisfy(unit ->
            assertThat(unit.rawText()).contains("内部资料 第 2 页"));
}

@Test
void keepsOcrBodyWhenNoReliableHeadingExists() throws Exception {
    PdfDocumentParser parser = new PdfDocumentParser(ocrWithBlocks(
            block("扫描件退款正文", 0.91, box(80, 120, 500, 150))), 3);

    ParsedDocument parsed = parser.parse(new ParseRequest(
            "扫描规则.pdf", "pdf", "application/pdf", imagePdf()));

    assertThat(parsed.units()).singleElement().satisfies(unit -> {
        assertThat(unit.text()).contains("扫描件退款正文");
        assertThat(unit.titlePath()).isEqualTo("扫描规则");
        assertThat(unit.ocrConfidence()).isEqualTo(0.91D);
    });
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
mvn -Dtest=PdfAndImageParserTest test
```

Expected: title-path and repeated-margin assertions fail against `pdfbox-3-ocr-v1` behavior.

- [ ] **Step 3: Wire the layout pipeline**

Annotate `PdfLayoutExtractor`, `PdfRepeatedArtifactDetector`, and `PdfStructureAnalyzer` as Spring components. The public Spring constructor receives these collaborators together with `OcrClient` and `ParsingProperties`; retain a package-private test constructor that supplies the production helper implementations so existing focused parser tests stay lightweight.

The parse flow becomes:

```java
List<PdfPageLayout> pages = new ArrayList<>();
for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
    PdfPageLayout page = layoutExtractor.extract(document, pageIndex);
    if (meaningfulCharacters(page.rawText()) < MIN_TEXT_CHARACTERS) {
        RenderedPage rendered = renderPng(renderer, pageIndex);
        OcrResult result = ocrClient.recognize(
                UUID.randomUUID().toString(), "ch", rendered.png());
        page = layoutExtractor.fromOcr(
                pageIndex + 1,
                document.getPage(pageIndex).getCropBox().getWidth(),
                document.getPage(pageIndex).getCropBox().getHeight(),
                rendered.width(), rendered.height(),
                result);
        ocrRequired = true;
    }
    pages.add(page);
}
return new ParsedDocument(structureAnalyzer.analyze(request.filename(), pages), ocrRequired);
```

Change `renderPng` to return a small `RenderedPage` value containing PNG bytes plus rendered pixel width and height. `fromOcr` receives those image dimensions in addition to PDF page dimensions. It maps four-point OCR pixel boxes into PDF-relative x/y/width/height using explicit X/Y scale factors, uses normalized box height only as a conservative size proxy, preserves block confidence, and keeps input order when boxes are missing. OCR lines cannot become headings from size alone: they still need numbering or spacing evidence; otherwise they inherit the last reliable title path or the document root.

- [ ] **Step 4: Bump parser version**

Change:

```java
return "pdfbox-3-ocr-v2";
```

- [ ] **Step 5: Verify focused parser tests and commit**

```powershell
mvn -Dtest=PdfAndImageParserTest,PdfLayoutExtractorTest,PdfRepeatedArtifactDetectorTest,PdfStructureAnalyzerTest test
```

Expected: PASS with no OCR regression.

```powershell
git add src/main/java/com/xjjk/knowledge/document/parser/PdfDocumentParser.java `
        src/main/java/com/xjjk/knowledge/document/parser/PdfLayoutExtractor.java `
        src/test/java/com/xjjk/knowledge/document/parser/PdfAndImageParserTest.java
git commit -m "feat: enable layout-aware PDF parsing"
```

---

### Task 6: Verify Chunk and Index Metadata Propagation

**Files:**
- Modify: `src/test/java/com/xjjk/knowledge/document/processing/StructuralChunkerTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndexTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndexTest.java`

- [ ] **Step 1: Add a failing chunk propagation assertion**

```java
ParsedUnit unit = new ParsedUnit(
        "PDF_SECTION", 1, "第 8 页", "退款规范 > 5 优惠处理",
        "部分退款后不满足满减门槛时回收优惠。", null, false,
        "退款规范 第8页 部分退款后不满足满减门槛时回收优惠。");

List<DocumentChunk> chunks = chunker.chunk(1L, 5L, 7L, List.of(unit));

assertThat(chunks).singleElement().satisfies(chunk -> {
    assertThat(chunk.titlePath()).isEqualTo("退款规范 > 5 优惠处理");
    assertThat(chunk.locationLabel()).isEqualTo("第 8 页");
    assertThat(chunk.text()).startsWith("退款规范 > 5 优惠处理");
});
```

- [ ] **Step 2: Run and confirm whether existing propagation already passes**

```powershell
mvn -Dtest=StructuralChunkerTest test
```

Expected: the test should pass because `StructuralChunker` already copies `titlePath` and prefixes it into content. If it fails, change only the specific propagation defect revealed by the assertion.

- [ ] **Step 3: Assert both indexes receive title path**

In the Elasticsearch test, use an `IndexChunk` with `titlePath="退款规范 > 5 优惠处理"` and assert the bulk request contains the exact serialized path. In `MilvusVectorIndexTest`, extend `CapturingGateway` to retain the `MilvusVectorRow` values passed to `upsert`, then assert the captured row's `IndexChunk.titlePath()` is exactly the same path.

- [ ] **Step 4: Run index contract tests**

```powershell
mvn -Dtest=StructuralChunkerTest,ElasticsearchKeywordIndexTest,MilvusVectorIndexTest test
```

Expected: PASS.

- [ ] **Step 5: Commit test coverage**

```powershell
git add src/test/java/com/xjjk/knowledge/document/processing/StructuralChunkerTest.java `
        src/test/java/com/xjjk/knowledge/retrieval/index/ElasticsearchKeywordIndexTest.java `
        src/test/java/com/xjjk/knowledge/retrieval/index/MilvusVectorIndexTest.java
git commit -m "test: verify PDF title metadata indexing"
```

---

### Task 7: Full Regression Verification

**Files:**
- No production changes expected.

- [ ] **Step 1: Run all parser and chunk tests**

```powershell
mvn -Dtest=PdfAndImageParserTest,PdfLayoutExtractorTest,PdfRepeatedArtifactDetectorTest,PdfStructureAnalyzerTest,StructuralChunkerTest test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run the complete test suite**

```powershell
mvn test
```

Expected: build success with all tests passing and no new warnings attributable to the change.

- [ ] **Step 3: Check formatting and working-tree scope**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the user's pre-existing `src/main/resources/application-local.yml` change may remain outside committed work.

---

### Task 8: Rebuild and Validate the Four Business PDFs

**Files:**
- Runtime data only; do not edit source files.

- [ ] **Step 1: Restart the knowledge service with the new parser**

Verify:

```powershell
Invoke-RestMethod http://127.0.0.1:8085/actuator/health
```

Expected: status `UP`.

- [ ] **Step 2: Reprocess the four PDFs through the supported admin workflow**

Use the existing management UI/API to create new versions or delete and re-upload the disposable test documents from `C:\Users\shwfo\Desktop\PDF`. Do not mutate derived tables manually; ingestion must run through the existing PARSE and INDEX tasks.

- [ ] **Step 3: Verify MySQL parse results**

Run:

```sql
SELECT original_filename, parser_version, status, unit_count, chunk_count,
       failure_stage, last_error_code
FROM kb_document_version
WHERE file_extension = 'pdf'
ORDER BY id DESC
LIMIT 4;

SELECT v.original_filename,
       SUM(u.title_path IS NOT NULL AND u.title_path <> '') AS titled_units,
       COUNT(*) AS total_units,
       SUM(u.effective_text REGEXP '第[[:space:]]*[0-9]+[[:space:]]*页') AS leaked_page_markers
FROM kb_document_version v
JOIN kb_document_unit u ON u.version_id = v.id
WHERE v.id IN (
    SELECT id FROM (
        SELECT id FROM kb_document_version
        WHERE file_extension = 'pdf' ORDER BY id DESC LIMIT 4
    ) latest
)
GROUP BY v.id, v.original_filename;
```

Expected: all four versions are `PUBLISHED`, parser version is `pdfbox-3-ocr-v2`, failures are null, business section units have nonblank paths, and repeated page markers do not leak into effective text.

- [ ] **Step 4: Verify Elasticsearch and Milvus parity**

Compare the four version IDs across:

```text
MySQL kb_chunk count
Elasticsearch knowledge_chunks_published_v2 count grouped by versionId
Milvus knowledge_chunks_published_v2 query grouped by version_id
```

Expected: exact per-version count equality in all three stores.

- [ ] **Step 5: Run retrieval acceptance questions**

Run at least these cases against the published layer:

```text
订单：用户重复点击提交订单，系统如何避免创建多笔有效订单？
物流：物流轨迹超过24小时没有更新，是否可以直接认定为延误并赔付？
退款：部分退款后订单不再满足满减门槛，优惠和积分应该如何处理？
售后：商品发生冒烟或起火等安全问题时，售后应该如何升级处理？
跨章节：用户申请退货退款时，售后验收和退款金额计算分别需要满足什么条件？
拒答：公司董事长的家庭住址是什么？
```

Expected: the first five cases return correctly scoped evidence with document title, nonblank title path, and page location; the final unsupported question returns `answerable=false` with no fabricated evidence.

- [ ] **Step 6: Record final evidence**

Capture in the completion report:

- test counts and Maven result;
- four document versions and chunk counts;
- title-path coverage and header/footer leakage counts;
- ES/Milvus count parity;
- retrieval result code, degradation mode, top evidence path, and latency;
- any remaining limitations for multi-column PDFs or low-confidence OCR.
