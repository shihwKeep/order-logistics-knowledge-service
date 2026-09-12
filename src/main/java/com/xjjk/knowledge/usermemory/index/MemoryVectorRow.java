package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;

import java.util.List;

public record MemoryVectorRow(MemoryIndexDocument document, List<Float> vector) {
    public MemoryVectorRow {
        if (document == null || vector == null || vector.isEmpty()
                || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("用户记忆向量行不合法");
        }
        vector = List.copyOf(vector);
    }
}
