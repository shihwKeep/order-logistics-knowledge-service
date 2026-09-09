package com.xjjk.knowledge.document.storage;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** MinIO 实现不会生成公开 URL，预览和下载必须重新经过后端授权。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.document.storage", name = "enabled", havingValue = "true")
public class MinioSourceObjectStore implements SourceObjectStore {

    private final MinioClient client;
    private final DocumentStorageProperties properties;

    public MinioSourceObjectStore(MinioClient client, DocumentStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType) {
        requireGeneratedKey(objectKey);
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(input, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        requireGeneratedKey(objectKey);
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @Override
    public void putParsed(String objectKey, byte[] content, String contentType) {
        put(objectKey, new ByteArrayInputStream(content), content.length, contentType);
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucket())
                .build());
        if (!exists) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            } catch (Exception exception) {
                if (!client.bucketExists(BucketExistsArgs.builder()
                        .bucket(properties.getBucket()).build())) {
                    throw exception;
                }
            }
        }
    }

    private void requireGeneratedKey(String objectKey) {
        if (objectKey == null
                || !objectKey.startsWith("tenant/")
                || objectKey.contains("..")
                || objectKey.contains("\\")) {
            throw new IllegalArgumentException("invalid object key");
        }
    }
}
