package com.xjjk.knowledge.retrieval.indexing;

/** 当前 Worker 已失去数据库任务租约，必须停止继续写索引或提交版本状态。 */
public class IngestionLeaseLostException extends RuntimeException {
    public IngestionLeaseLostException() {
        super("文档索引任务租约已失效");
    }
}
