package com.xjjk.knowledge.retrieval.index;

public record MilvusCollectionSpec(String name, int dimension, String metric, String primaryKeyField) {
}
