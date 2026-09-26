package com.xjjk.knowledge.document.task;

public record OutboxEvent(long id, long taskId, String eventType, int attemptCount) {}
