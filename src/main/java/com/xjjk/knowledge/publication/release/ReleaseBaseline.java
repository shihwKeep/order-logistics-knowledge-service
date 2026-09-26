package com.xjjk.knowledge.publication.release;

/** 创建 Release 时在知识库行锁下读取的并发基线。 */
public record ReleaseBaseline(
        Long currentReleaseId,
        int rowVersion,
        String currentManifestSha256) {
}
