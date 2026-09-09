package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MybatisDocumentManagementRepositoryTest {

    @Test
    void publishedVersionCannotBeCorrectedInPlace() {
        DocumentManagementMapper mapper = mock(DocumentManagementMapper.class);
        TextNormalizer normalizer = mock(TextNormalizer.class);
        when(mapper.lockCorrectionState(1L, 2L, 3L, 4L))
                .thenReturn(new CorrectionVersionState(2, "PUBLISHED"));
        MybatisDocumentManagementRepository repository =
                new MybatisDocumentManagementRepository(mapper, normalizer);

        assertThatThrownBy(() -> repository.correctUnit(
                1L, 2L, 3L, 4L, 9L, "修改后的正文", 10567L, "request-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().code())
                                .isEqualTo("PUBLICATION_CONFLICT"));

        verifyNoMoreInteractions(normalizer);
    }
}
