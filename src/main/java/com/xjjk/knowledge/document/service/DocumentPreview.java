package com.xjjk.knowledge.document.service;

import java.io.IOException;
import java.io.InputStream;

/** 已完成租户鉴权的源文件流；对象键永远不会返回浏览器。 */
public record DocumentPreview(
        InputStream content,
        String filename,
        String contentType,
        long contentLength,
        boolean inline) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        content.close();
    }
}
