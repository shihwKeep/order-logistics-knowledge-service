package com.xjjk.knowledge.retrieval.indexing;

/** 一次通过双索引校验的版本元数据，随 READY 状态原子持久化。 */
public record ReadyIndexMetadata(
        String model,
        int dimension,
        String instructionVersion,
        String manifestSha256) {
}
