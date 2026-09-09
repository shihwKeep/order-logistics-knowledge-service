package com.xjjk.knowledge.document.processing;

@FunctionalInterface
public interface TokenEstimator {
    int estimate(String text);
}
