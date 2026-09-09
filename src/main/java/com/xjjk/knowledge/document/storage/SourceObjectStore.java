package com.xjjk.knowledge.document.storage;

import java.io.InputStream;

/** 原件和解析产物存储边界；调用者只能传入后端生成的对象键。 */
public interface SourceObjectStore {
    void put(String objectKey, InputStream input, long size, String contentType);

    InputStream get(String objectKey);

    void putParsed(String objectKey, byte[] content, String contentType);
}
