package com.xjjk.knowledge.document.storage;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;

import java.io.InputStream;

/** 未启用对象存储时保留明确的 503 行为，避免服务因可选本地依赖无法启动。 */
final class UnavailableSourceObjectStore implements SourceObjectStore {
    @Override
    public void put(String objectKey, InputStream input, long size, String contentType) {
        throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    }

    @Override
    public InputStream get(String objectKey) {
        throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    }

    @Override
    public void putParsed(String objectKey, byte[] content, String contentType) {
        throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    }
}
