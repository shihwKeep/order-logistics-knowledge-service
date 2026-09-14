package com.xjjk.knowledge.cloud.budget;

public class CloudModelBudgetExceededException extends RuntimeException {
    public CloudModelBudgetExceededException() {
        super("知识检索模型本月硬额度已用尽");
    }
}
