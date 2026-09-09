package com.xjjk.knowledge.document.domain;

/** 通过上传校验后的原文件元数据，不包含文件正文。 */
public record SourceFile(
        String originalFilename,
        String extension,
        String mimeType,
        long size,
        String sha256) {
}
