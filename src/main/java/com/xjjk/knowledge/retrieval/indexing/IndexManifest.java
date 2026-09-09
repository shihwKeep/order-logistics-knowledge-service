package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.retrieval.model.IndexChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对稳定 Chunk ID 和正文哈希生成有序清单，供 ES、Milvus 与 MySQL 三方对账。 */
final class IndexManifest {
    private IndexManifest() {
    }

    static Map<String, String> fingerprints(List<IndexChunk> chunks) {
        Map<String, String> result = new LinkedHashMap<>();
        for (IndexChunk chunk : chunks) {
            if (result.put(chunk.chunkId(), chunk.contentSha256()) != null) {
                throw new IllegalStateException("存在重复 Chunk ID: " + chunk.chunkId());
            }
        }
        return Map.copyOf(result);
    }

    static String sha256(List<IndexChunk> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (IndexChunk chunk : chunks) {
                digest.update(chunk.chunkId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(chunk.contentSha256().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
