package com.xjjk.knowledge.document.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 仅依据跨页重复与页边区域识别页眉、页脚，避免误删正文中的重复条款。 */
@Component
public class PdfRepeatedArtifactDetector {
    private static final double TOP_BAND = 0.12D;
    private static final double BOTTOM_BAND = 0.88D;
    private static final double REQUIRED_PAGE_RATIO = 0.60D;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:第\\s*)?\\d+(?:\\s*页)?$|^page\\s+\\d+$",
            Pattern.CASE_INSENSITIVE);

    public Set<PdfTextLine> detect(List<PdfPageLayout> pages) {
        if (pages == null || pages.size() < 3) {
            return Set.of();
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        List<PageLine> pageNumberLines = new ArrayList<>();
        for (PdfPageLayout page : pages) {
            for (PdfTextLine line : page.lines()) {
                if (!inMarginBand(line) || line.text().isBlank()) {
                    continue;
                }
                String key = normalizedKey(line);
                candidates.computeIfAbsent(key, ignored -> new Candidate())
                        .add(page.pageNumber(), line);
                if (PAGE_NUMBER.matcher(line.text().strip()).matches()) {
                    pageNumberLines.add(new PageLine(page.pageNumber(), line));
                }
            }
        }

        int requiredPages = (int) Math.ceil(pages.size() * REQUIRED_PAGE_RATIO);
        Set<PdfTextLine> artifacts = new LinkedHashSet<>();
        candidates.values().stream()
                .filter(candidate -> candidate.pageNumbers.size() >= requiredPages)
                .forEach(candidate -> artifacts.addAll(candidate.lines));

        long numberedPages = pageNumberLines.stream().map(PageLine::pageNumber).distinct().count();
        if (numberedPages >= requiredPages) {
            pageNumberLines.forEach(pageLine -> artifacts.add(pageLine.line()));
        }
        return Set.copyOf(artifacts);
    }

    private boolean inMarginBand(PdfTextLine line) {
        return line.yRatio() <= TOP_BAND || line.yRatio() >= BOTTOM_BAND;
    }

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

    private static final class Candidate {
        private final Set<Integer> pageNumbers = new LinkedHashSet<>();
        private final List<PdfTextLine> lines = new ArrayList<>();

        private void add(int pageNumber, PdfTextLine line) {
            pageNumbers.add(pageNumber);
            lines.add(line);
        }
    }

    private record PageLine(int pageNumber, PdfTextLine line) {
    }
}
