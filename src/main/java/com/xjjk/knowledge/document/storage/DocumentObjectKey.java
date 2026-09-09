package com.xjjk.knowledge.document.storage;

/** 统一生成不含用户文件名的租户隔离对象键。 */
public final class DocumentObjectKey {

    private DocumentObjectKey() {
    }

    public static String source(long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        return prefix(tenantId, knowledgeBaseId, documentId, versionId) + "/source";
    }

    public static String parsed(long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        return prefix(tenantId, knowledgeBaseId, documentId, versionId) + "/parsed";
    }

    private static String prefix(long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        requirePositive(tenantId, "tenantId");
        requirePositive(knowledgeBaseId, "knowledgeBaseId");
        requirePositive(documentId, "documentId");
        requirePositive(versionId, "versionId");
        return "tenant/" + tenantId
                + "/knowledge-base/" + knowledgeBaseId
                + "/document/" + documentId
                + "/version/" + versionId;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
