package com.xjjk.knowledge.document.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.xjjk.knowledge.document.parser.ParsedUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralChunkerTest {

    @Test
    void prefixesTitlePathAndNeverSplitsBusinessIdentifier() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setTargetTokens(12);
        properties.setMaxTokens(20);
        properties.setOverlapTokens(3);
        StructuralChunker chunker = new StructuralChunker(properties, text -> text.length());
        ParsedUnit unit = new ParsedUnit(
                "TEXT", 1, "第 1 节", "售后规则 / 退款", "请核对订单 XJTS0120260903000395 后再提交退款申请。", null, false);

        List<DocumentChunk> chunks = chunker.chunk(1L, 2L, 3L, List.of(unit));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().text()).startsWith("售后规则 / 退款\n");
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.text()).contains("XJTS0120260903000395"));
        assertThat(chunks).noneSatisfy(chunk -> assertThat(chunk.text()).contains("XJTS01202609\n"));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.identity()).startsWith("1:2:3:");
            assertThat(chunk.sha256()).hasSize(64);
        });
    }

    @Test
    void repeatsTableHeaderWhenRowsAreSplit() {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setTargetTokens(25);
        properties.setMaxTokens(35);
        properties.setOverlapTokens(0);
        StructuralChunker chunker = new StructuralChunker(properties, text -> text.length());
        ParsedUnit table = new ParsedUnit(
                "SHEET", 1, "工作表 商品", "库存", "商品编码 | 库存\nA001 | 10\nA002 | 20\nA003 | 30", null, false);

        List<DocumentChunk> chunks = chunker.chunk(1L, 2L, 3L, List.of(table));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text()).contains("商品编码 | 库存"));
    }
}
