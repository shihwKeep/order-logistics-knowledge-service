package com.xjjk.knowledge.document.parser;

import java.util.Arrays;

/** 解析器输入使用内存副本，避免解析器持有上传请求流。 */
public record ParseRequest(String filename, String extension, String mimeType, byte[] content) {
    public ParseRequest {
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
