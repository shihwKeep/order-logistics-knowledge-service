package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.parser.ParsedUnit;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestionArtifactRepositoryTest {

    @Test
    void persistsRawAndEffectiveTextSeparately() {
        IngestionArtifactMapper mapper = mock(IngestionArtifactMapper.class);
        IngestionArtifactRepository repository = new IngestionArtifactRepository(mapper, new TextNormalizer());
        ParsedUnit unit = new ParsedUnit(
                "PDF_SECTION", 1, "第 2 页", "退款规范 > 退款资格",
                "退款资格正文", null, false,
                "退款规范\n第 2 页\n退款资格正文");

        repository.replaceParsedArtifacts(
                version(), new ParsedDocument(List.of(unit), false), List.of(), "pdfbox-3-ocr-v2");

        ArgumentCaptor<DocumentUnitEntity> captor = ArgumentCaptor.forClass(DocumentUnitEntity.class);
        verify(mapper).insertUnit(captor.capture());
        assertThat(captor.getValue().getRawText()).isEqualTo("退款规范\n第 2 页\n退款资格正文");
        assertThat(captor.getValue().getEffectiveText()).isEqualTo("退款资格正文");
    }

    private DocumentVersion version() {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.PARSING,
                "退款规范.pdf", "pdf", "application/pdf", 1L, "sha", "key", null,
                null, null, null, null, null, null, null,
                false, 0, 0, 0, null, null, null, 1L, now, now);
    }
}
