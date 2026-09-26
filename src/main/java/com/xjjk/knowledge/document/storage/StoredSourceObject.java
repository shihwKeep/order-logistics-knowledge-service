package com.xjjk.knowledge.document.storage;

import java.time.Instant;

/** MinIO 中可参与孤儿清理判断的原文件对象元数据。 */
public record StoredSourceObject(String key, Instant lastModified) {
}
