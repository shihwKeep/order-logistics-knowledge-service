package com.xjjk.knowledge.retrieval.service;

import org.springframework.stereotype.Repository;

/** 查询日志不保存用户问题、知识正文和向量。 */
@Repository
public class SearchLogRecorder {
    private final SearchLogMapper mapper;

    public SearchLogRecorder(SearchLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(SearchLogEntry entry) {
        mapper.insert(entry);
    }
}
