package com.xjjk.knowledge.audit;

import java.util.List;

public record AuditPage(long total, int offset, int limit, List<AuditLogEntry> items) {
    public AuditPage {
        items = List.copyOf(items);
    }
}
