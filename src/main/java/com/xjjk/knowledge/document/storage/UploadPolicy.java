package com.xjjk.knowledge.document.storage;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 上传文件白名单和内容签名校验，不信任浏览器声明的 MIME。 */
public class UploadPolicy {

    private static final Set<String> DANGEROUS_SEGMENTS = Set.of(
            "exe", "com", "bat", "cmd", "ps1", "scr", "dll", "jar", "js", "vbs");
    private static final Map<String, String> CANONICAL_MIME = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("csv", "text/csv"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"));

    private final long maxFileSize;

    public UploadPolicy(long maxFileSize) {
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("maxFileSize must be positive");
        }
        this.maxFileSize = maxFileSize;
    }

    public ValidatedUpload validate(String filename, String declaredMimeType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_EMPTY);
        }
        if (content.length > maxFileSize) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_TOO_LARGE);
        }
        String safeFilename = requireSafeFilename(filename);
        String extension = extension(safeFilename);
        String canonicalMime = CANONICAL_MIME.get(extension);
        if (canonicalMime == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
        }
        if (!signatureMatches(extension, content)) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_SIGNATURE_MISMATCH);
        }
        if (declaredMimeType != null
                && !declaredMimeType.isBlank()
                && !mimeCompatible(extension, declaredMimeType)) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_SIGNATURE_MISMATCH);
        }
        return new ValidatedUpload(
                safeFilename,
                extension.equals("jpeg") ? "jpg" : extension,
                canonicalMime,
                content.length,
                sha256(content));
    }

    private String requireSafeFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
        }
        String normalized = filename.trim().replace('\\', '/');
        if (normalized.contains("/") || normalized.indexOf('\0') >= 0) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
        }
        String[] segments = normalized.toLowerCase(Locale.ROOT).split("\\.");
        for (int index = 0; index < segments.length - 1; index++) {
            if (DANGEROUS_SEGMENTS.contains(segments[index])) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
            }
        }
        return normalized;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNSUPPORTED_TYPE);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean signatureMatches(String extension, byte[] content) {
        return switch (extension) {
            case "pdf" -> startsWith(content, new byte[]{'%', 'P', 'D', 'F', '-'});
            case "png" -> startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            case "jpg", "jpeg" -> startsWith(content, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "xls" -> startsWith(content, new byte[]{
                    (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                    (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
            case "docx" -> zipContains(content, "word/document.xml");
            case "xlsx" -> zipContains(content, "xl/workbook.xml");
            case "pptx" -> zipContains(content, "ppt/presentation.xml");
            case "txt", "md", "csv", "html", "htm" -> looksLikeText(content);
            default -> false;
        };
    }

    private boolean mimeCompatible(String extension, String declaredMimeType) {
        String mime = declaredMimeType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (mime.equals("application/octet-stream")) {
            return true;
        }
        if (extension.equals("md") && mime.equals("text/plain")) {
            return true;
        }
        if ((extension.equals("jpg") || extension.equals("jpeg")) && mime.equals("image/jpeg")) {
            return true;
        }
        return CANONICAL_MIME.get(extension).equals(mime);
    }

    private boolean zipContains(byte[] content, String expectedEntry) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int inspected = 0;
            while ((entry = zip.getNextEntry()) != null && inspected++ < 10_000) {
                if (expectedEntry.equals(entry.getName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean looksLikeText(byte[] content) {
        int sampleSize = Math.min(content.length, 8192);
        for (int index = 0; index < sampleSize; index++) {
            if (content[index] == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, 0, sampleSize));
            return true;
        } catch (CharacterCodingException ignored) {
            return true; // 后续文本解析器会尝试 GB18030。
        }
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
