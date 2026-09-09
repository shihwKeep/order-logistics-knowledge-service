package com.xjjk.knowledge.document.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentObjectKeyTest {

    @Test
    void buildsTenantAndVersionBoundKeysWithoutUsingFilename() {
        assertThat(DocumentObjectKey.source(3L, 8L, 13L, 21L))
                .isEqualTo("tenant/3/knowledge-base/8/document/13/version/21/source");
        assertThat(DocumentObjectKey.parsed(3L, 8L, 13L, 21L))
                .isEqualTo("tenant/3/knowledge-base/8/document/13/version/21/parsed");
    }
}
