package com.xjjk.knowledge.document.storage;

/** 通过扩展名、MIME 和文件签名三重校验后的上传元数据。 */
public record ValidatedUpload(
        String originalFilename,
        String extension,
        String mimeType,
        long size,
        String sha256) {
}
