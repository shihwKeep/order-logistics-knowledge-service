package com.xjjk.knowledge.document.processing;

import com.xjjk.knowledge.document.parser.ParsedUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 优先沿单元、段落和表格行切分；连续业务编号作为不可拆原子。 */
@Component
public class StructuralChunker {
    private static final Pattern ATOM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{5,}|.", Pattern.DOTALL);
    private final ChunkingProperties properties;
    private final TokenEstimator tokenEstimator;
    private final TextNormalizer normalizer = new TextNormalizer();

    public StructuralChunker(ChunkingProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public List<DocumentChunk> chunk(long tenantId, long documentId, long versionId, List<ParsedUnit> units) {
        List<DraftChunk> drafts = new ArrayList<>();
        for (ParsedUnit unit : units) {
            String normalized = normalizer.normalize(unit.text());
            if (normalized.isBlank()) {
                continue;
            }
            if (looksLikeTable(normalized)) {
                drafts.addAll(splitTable(unit, normalized));
            } else {
                drafts.addAll(splitText(unit, normalized));
            }
        }
        List<DocumentChunk> result = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            DraftChunk draft = drafts.get(index);
            String identity = tenantId + ":" + documentId + ":" + versionId + ":" + index;
            result.add(new DocumentChunk(
                    identity, index, draft.unit.unitIndex(), draft.unit.locationLabel(), draft.unit.titlePath(),
                    draft.text, tokenEstimator.estimate(draft.text), normalizer.sha256(draft.text)));
        }
        return List.copyOf(result);
    }

    private List<DraftChunk> splitTable(ParsedUnit unit, String text) {
        String[] lines = text.split("\n");
        String header = lines[0];
        String prefix = prefix(unit);
        List<DraftChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(prefix).append(header);
        for (int index = 1; index < lines.length; index++) {
            String candidate = current + "\n" + lines[index];
            if (tokenEstimator.estimate(candidate) > properties.getTargetTokens() && current.length() > prefix.length() + header.length()) {
                chunks.add(new DraftChunk(unit, current.toString()));
                current = new StringBuilder(prefix).append(header);
            }
            current.append('\n').append(lines[index]);
        }
        if (!current.isEmpty()) {
            chunks.add(new DraftChunk(unit, current.toString()));
        }
        return chunks;
    }

    private List<DraftChunk> splitText(ParsedUnit unit, String text) {
        String prefix = prefix(unit);
        List<String> atoms = atoms(text);
        List<DraftChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(prefix);
        boolean hasNewContent = false;
        for (String atom : atoms) {
            String candidate = current + atom;
            if (tokenEstimator.estimate(candidate) > properties.getMaxTokens() && current.length() > prefix.length()) {
                String completed = current.toString().stripTrailing();
                chunks.add(new DraftChunk(unit, completed));
                String overlap = overlap(completed, properties.getOverlapTokens());
                current = new StringBuilder(prefix).append(overlap);
                hasNewContent = false;
            }
            current.append(atom);
            hasNewContent = true;
            if (tokenEstimator.estimate(current.toString()) >= properties.getTargetTokens()
                    && endsAtBoundary(atom)) {
                chunks.add(new DraftChunk(unit, current.toString().stripTrailing()));
                String overlap = overlap(current.toString(), properties.getOverlapTokens());
                current = new StringBuilder(prefix).append(overlap);
                hasNewContent = false;
            }
        }
        if (hasNewContent) {
            chunks.add(new DraftChunk(unit, current.toString().stripTrailing()));
        }
        return chunks;
    }

    private static boolean looksLikeTable(String text) {
        String[] lines = text.split("\n");
        return lines.length > 1 && lines[0].contains("|") && lines[1].contains("|");
    }

    private static String prefix(ParsedUnit unit) {
        return unit.titlePath() == null || unit.titlePath().isBlank() ? "" : unit.titlePath().strip() + "\n";
    }

    private static List<String> atoms(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = ATOM.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static boolean endsAtBoundary(String atom) {
        return atom.equals("。") || atom.equals("！") || atom.equals("？") || atom.equals("\n") || atom.equals(";");
    }

    private static String overlap(String text, int requestedTokens) {
        if (requestedTokens <= 0 || text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, text.length() - requestedTokens);
        while (start > 0 && start < text.length()
                && Character.isLetterOrDigit(text.charAt(start - 1))
                && Character.isLetterOrDigit(text.charAt(start))) {
            start--;
        }
        return text.substring(start);
    }

    private record DraftChunk(ParsedUnit unit, String text) {}
}
