package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;

public record PublicationTarget(KnowledgeDocument document, DocumentVersion version) {
}
