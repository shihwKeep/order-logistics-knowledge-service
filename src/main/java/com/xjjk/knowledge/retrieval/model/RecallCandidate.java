package com.xjjk.knowledge.retrieval.model;

public record RecallCandidate(IndexChunk chunk, double score, RecallSource source) {
}
