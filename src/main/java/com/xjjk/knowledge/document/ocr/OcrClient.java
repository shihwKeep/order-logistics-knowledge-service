package com.xjjk.knowledge.document.ocr;

/** OCR 边界只接收单张图片，调用方负责 PDF 分页渲染与失败归属。 */
@FunctionalInterface
public interface OcrClient {
    OcrResult recognize(String requestId, String language, byte[] image);
}
