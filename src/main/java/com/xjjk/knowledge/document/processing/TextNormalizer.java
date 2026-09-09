package com.xjjk.knowledge.document.processing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 只规范排版噪声，不改写业务措辞、编号或表格的行结构。 */
@Component
public class TextNormalizer {
    public String normalize(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean previousBlank = false;
        for (String line : lines) {
            String clean = line.strip().replaceAll("[\\t \\x0B\\f]+", " ");
            if (clean.isEmpty()) {
                if (!previousBlank && !result.isEmpty()) {
                    result.append('\n');
                }
                previousBlank = true;
            } else {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(clean);
                previousBlank = false;
            }
        }
        return result.toString().strip();
    }

    public String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
