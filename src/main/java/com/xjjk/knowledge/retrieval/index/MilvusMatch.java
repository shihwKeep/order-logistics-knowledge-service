package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;

public record MilvusMatch(IndexChunk chunk, double score) {
}
