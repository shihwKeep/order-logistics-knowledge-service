package com.xjjk.knowledge.usermemory.index;

public record MemoryMilvusSpec(
        String name, int dimension, String metric, String primaryKeyField) {
}
