package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;

import java.util.List;

public record MilvusVectorRow(IndexChunk chunk, List<Float> vector) {
    public MilvusVectorRow {
        vector = List.copyOf(vector);
    }
}
