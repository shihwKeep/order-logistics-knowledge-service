package com.xjjk.knowledge.document.task;

public record OutboxEvent(long id, long taskId, int attemptCount) {}
