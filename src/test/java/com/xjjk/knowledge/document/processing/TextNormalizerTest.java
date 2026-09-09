package com.xjjk.knowledge.document.processing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {
    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void normalizesWhitespaceWithoutDestroyingParagraphOrTableBoundaries() {
        String source = "  退款规则\r\n\r\n  第一条\t  七日内可退。 \r\n\r\n名称 | 时效\r\n退款 | 7天  ";

        String normalized = normalizer.normalize(source);

        assertThat(normalized).isEqualTo("退款规则\n\n第一条 七日内可退。\n\n名称 | 时效\n退款 | 7天");
        assertThat(normalizer.sha256(normalized)).hasSize(64).isEqualTo(normalizer.sha256(normalized));
    }
}
