package com.xjjk.knowledge.document.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 将页面视觉行转换为按页定位、按章节分段的可检索单元。 */
@Component
public class PdfStructureAnalyzer {
    private static final int MAX_HEADING_LEVEL = 4;
    private static final int MAX_TITLE_PATH_LENGTH = 1000;
    private static final Pattern DECIMAL_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)(?:[\\s、.．]|$).*$");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百千万零〇0-9]+[章节篇部分].*$");
    private static final Pattern HEADING_NUMBER = Pattern.compile(
            "^(?:\\d+(?:\\.\\d+)*(?:[\\s、.．]|$)|第[一二三四五六七八九十百千万零〇0-9]+[章节篇部分]).*$");
    private static final Pattern BODY_LIST_MARKER = Pattern.compile("^\\d+[.、．]\\s+.*$");
    private static final Pattern TABLE_OF_CONTENTS = Pattern.compile(
            "^(?:目录|目次|contents|table\\s+of\\s+contents)$", Pattern.CASE_INSENSITIVE);

    private final PdfRepeatedArtifactDetector artifactDetector;

    public PdfStructureAnalyzer(PdfRepeatedArtifactDetector artifactDetector) {
        this.artifactDetector = artifactDetector;
    }

    public List<ParsedUnit> analyze(String filename, List<PdfPageLayout> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        Set<PdfTextLine> artifacts = artifactDetector.detect(pages);
        double bodyFont = bodyFont(pages, artifacts);
        List<Double> headingFontLevels = headingFontLevels(pages, artifacts, bodyFont);
        String root = documentRoot(filename);
        String[] headingStack = new String[MAX_HEADING_LEVEL];
        List<ParsedUnit> units = new ArrayList<>();
        int nextIndex = 1;

        for (PdfPageLayout page : pages) {
            boolean tableOfContentsPage = isTableOfContentsPage(page, artifacts);
            // 目录是独立导航内容：使用临时标题栈，既不继承封面层级，也不污染后续正文。
            String[] pageHeadingStack = tableOfContentsPage
                    ? new String[MAX_HEADING_LEVEL]
                    : headingStack;
            PageFragments fragments = analyzePage(
                    page, artifacts, bodyFont, headingFontLevels, root,
                    pageHeadingStack, nextIndex, tableOfContentsPage);
            units.addAll(fragments.units());
            nextIndex = fragments.nextIndex();
        }
        return List.copyOf(units);
    }

    private PageFragments analyzePage(
            PdfPageLayout page,
            Set<PdfTextLine> artifacts,
            double bodyFont,
            List<Double> headingFontLevels,
            String root,
            String[] headingStack,
            int nextIndex,
            boolean tableOfContentsPage) {
        List<ParsedUnit> units = new ArrayList<>();
        StringBuilder pendingRaw = new StringBuilder();
        Fragment current = null;
        List<PdfTextLine> lines = page.lines();

        for (int index = 0; index < lines.size(); index++) {
            PdfTextLine line = lines.get(index);
            if (artifacts.contains(line)) {
                if (current == null) {
                    appendLine(pendingRaw, line.text());
                } else {
                    appendLine(current.raw, line.text());
                }
                continue;
            }

            double gapBefore = gapBefore(lines, index);
            double gapAfter = gapAfter(lines, index);
            if (isHeading(
                    line, bodyFont, gapBefore, gapAfter,
                    page.ocrConfidence() != null, tableOfContentsPage)) {
                if (current != null && current.hasContent()) {
                    units.add(toUnit(current, page, nextIndex++));
                }
                int level = headingLevel(line.text(), line.fontSize(), headingFontLevels);
                updateHeadingStack(headingStack, level, line.text().strip());
                current = new Fragment(titlePath(root, headingStack));
                movePendingRaw(pendingRaw, current.raw);
                appendLine(current.raw, line.text());
                appendLine(current.effective, line.text());
                continue;
            }

            if (current == null) {
                current = new Fragment(titlePath(root, headingStack));
                movePendingRaw(pendingRaw, current.raw);
            }
            appendLine(current.raw, line.text());
            appendLine(current.effective, line.text());
        }

        if (current != null) {
            movePendingRaw(pendingRaw, current.raw);
            if (current.hasContent()) {
                units.add(toUnit(current, page, nextIndex++));
            }
        } else if (!pendingRaw.isEmpty()) {
            // 仅含页边噪声的页面仍保留审计原文，但空 effectiveText 不会进入切片。
            Fragment auditOnly = new Fragment(titlePath(root, headingStack));
            movePendingRaw(pendingRaw, auditOnly.raw);
            units.add(toUnit(auditOnly, page, nextIndex++));
        }
        return new PageFragments(List.copyOf(units), nextIndex);
    }

    private ParsedUnit toUnit(Fragment fragment, PdfPageLayout page, int index) {
        return new ParsedUnit(
                "PDF_SECTION",
                index,
                "第 " + page.pageNumber() + " 页",
                fragment.titlePath,
                fragment.effective.toString().strip(),
                page.ocrConfidence(),
                page.lowConfidence(),
                fragment.raw.toString().strip());
    }

    /** 标题必须同时获得多种证据；正文大小的“1. 列表项”按保守策略保留为正文。 */
    private boolean isHeading(
            PdfTextLine line,
            double bodyFont,
            double gapBefore,
            double gapAfter,
            boolean ocrPage,
            boolean tableOfContentsPage) {
        String text = line.text().strip();
        if (text.isEmpty() || text.length() > 100 || endsLikeSentence(text)) {
            return false;
        }
        if (tableOfContentsPage) {
            return TABLE_OF_CONTENTS.matcher(text).matches();
        }
        boolean largerFont = line.fontSize() >= bodyFont * 1.18D;
        boolean typographyEvidence = largerFont || line.bold();
        boolean spacingEvidence = gapBefore >= bodyFont * 0.80D || gapAfter >= bodyFont * 0.80D;
        boolean numberingEvidence = HEADING_NUMBER.matcher(text).matches();
        if (ocrPage && !numberingEvidence && !spacingEvidence) {
            return false;
        }
        if (!typographyEvidence && BODY_LIST_MARKER.matcher(text).matches()) {
            return false;
        }
        // 与正文同字号的粗体表头也常有留白且文本较短；没有字号或章节编号证据时保守按正文处理。
        if (!largerFont && !numberingEvidence) {
            return false;
        }
        int score = 0;
        if (largerFont) {
            score += 2;
        }
        if (line.bold()) {
            score++;
        }
        if (spacingEvidence) {
            score++;
        }
        if (numberingEvidence) {
            score++;
        }
        if (line.width() <= line.pageWidth() * 0.75D) {
            score++;
        }
        return score >= 3;
    }

    private int headingLevel(String text, double fontSize, List<Double> headingFontLevels) {
        if (TABLE_OF_CONTENTS.matcher(text.strip()).matches()) {
            return 1;
        }
        Matcher decimal = DECIMAL_HEADING.matcher(text);
        if (decimal.matches()) {
            return Math.min(MAX_HEADING_LEVEL, decimal.group(1).split("\\.").length);
        }
        if (CHAPTER_HEADING.matcher(text).matches()) {
            return 1;
        }
        for (int index = 0; index < headingFontLevels.size(); index++) {
            if (Math.abs(headingFontLevels.get(index) - fontSize) < 0.01D) {
                return Math.min(MAX_HEADING_LEVEL, index + 1);
            }
        }
        return 1;
    }

    private void updateHeadingStack(String[] stack, int level, String heading) {
        int slot = Math.max(0, Math.min(stack.length - 1, level - 1));
        stack[slot] = heading;
        Arrays.fill(stack, slot + 1, stack.length, null);
    }

    private String titlePath(String root, String[] headings) {
        List<String> segments = new ArrayList<>();
        if (!root.isBlank()) {
            segments.add(root);
        }
        Arrays.stream(headings)
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> {
                    if (segments.isEmpty() || !segments.getLast().equals(value)) {
                        segments.add(value);
                    }
                });
        return limitTitlePath(segments);
    }

    private boolean isTableOfContentsPage(PdfPageLayout page, Set<PdfTextLine> artifacts) {
        return page.lines().stream()
                .filter(line -> !artifacts.contains(line))
                .map(PdfTextLine::text)
                .filter(text -> text != null && !text.isBlank())
                .limit(4)
                .anyMatch(text -> TABLE_OF_CONTENTS.matcher(text.strip()).matches());
    }

    /** 超长路径优先丢弃最旧的中间层级，始终保留当前最近标题。 */
    private String limitTitlePath(List<String> source) {
        List<String> segments = new ArrayList<>(source);
        while (joinedLength(segments) > MAX_TITLE_PATH_LENGTH && segments.size() > 2) {
            segments.remove(1);
        }
        String joined = String.join(" > ", segments);
        if (joined.length() <= MAX_TITLE_PATH_LENGTH) {
            return joined;
        }
        if (segments.size() == 1) {
            return joined.substring(0, MAX_TITLE_PATH_LENGTH);
        }
        String leaf = segments.getLast();
        if (leaf.length() >= MAX_TITLE_PATH_LENGTH) {
            return leaf.substring(leaf.length() - MAX_TITLE_PATH_LENGTH);
        }
        int rootBudget = MAX_TITLE_PATH_LENGTH - leaf.length() - 3;
        String root = segments.getFirst();
        String shortenedRoot = root.substring(0, Math.min(root.length(), Math.max(0, rootBudget)));
        return shortenedRoot.isEmpty() ? leaf : shortenedRoot + " > " + leaf;
    }

    private int joinedLength(List<String> segments) {
        return String.join(" > ", segments).length();
    }

    private double bodyFont(List<PdfPageLayout> pages, Set<PdfTextLine> artifacts) {
        List<Double> sizes = pages.stream()
                .flatMap(page -> page.lines().stream())
                .filter(line -> !artifacts.contains(line))
                .map(PdfTextLine::fontSize)
                .filter(size -> size > 0D)
                .sorted()
                .toList();
        return sizes.isEmpty() ? 10D : sizes.get((sizes.size() - 1) / 2);
    }

    private List<Double> headingFontLevels(
            List<PdfPageLayout> pages, Set<PdfTextLine> artifacts, double bodyFont) {
        return pages.stream()
                .flatMap(page -> page.lines().stream())
                .filter(line -> !artifacts.contains(line))
                .map(PdfTextLine::fontSize)
                .filter(size -> size >= bodyFont * 1.18D)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    private double gapBefore(List<PdfTextLine> lines, int index) {
        if (index == 0) {
            return Math.max(0D, lines.get(index).y());
        }
        PdfTextLine previous = lines.get(index - 1);
        return Math.max(0D, lines.get(index).y() - previous.y() - previous.height());
    }

    private double gapAfter(List<PdfTextLine> lines, int index) {
        if (index + 1 >= lines.size()) {
            return 0D;
        }
        PdfTextLine current = lines.get(index);
        return Math.max(0D, lines.get(index + 1).y() - current.y() - current.height());
    }

    private boolean endsLikeSentence(String text) {
        return text.matches(".*[。！？.!?；;]$");
    }

    private String documentRoot(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String clean = filename.strip();
        int extension = clean.lastIndexOf('.');
        return extension > 0 ? clean.substring(0, extension) : clean;
    }

    private void movePendingRaw(StringBuilder source, StringBuilder target) {
        if (!source.isEmpty()) {
            appendLine(target, source.toString());
            source.setLength(0);
        }
    }

    private void appendLine(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value.strip());
    }

    private static final class Fragment {
        private final String titlePath;
        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder effective = new StringBuilder();

        private Fragment(String titlePath) {
            this.titlePath = titlePath;
        }

        private boolean hasContent() {
            return !raw.isEmpty() || !effective.isEmpty();
        }
    }

    private record PageFragments(List<ParsedUnit> units, int nextIndex) {
    }
}
