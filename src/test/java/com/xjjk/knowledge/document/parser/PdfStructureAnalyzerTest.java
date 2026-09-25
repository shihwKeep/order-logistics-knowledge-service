package com.xjjk.knowledge.document.parser;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfStructureAnalyzerTest {
    private final PdfStructureAnalyzer analyzer =
            new PdfStructureAnalyzer(new PdfRepeatedArtifactDetector());

    @Test
    void createsMultipleSectionsAndCarriesHeadingAcrossPages() {
        List<ParsedUnit> units = analyzer.analyze("退款规范.pdf", List.of(
                layout(1,
                        line("1 退款资格", 18, true, 0.20),
                        line("用户应在有效期限内申请。", 10, false, 0.28),
                        line("1.1 申请主体", 14, true, 0.42),
                        line("申请人必须是订单所有者。", 10, false, 0.49)),
                layout(2,
                        line("还应校验是否存在重复申请。", 10, false, 0.20))));

        assertThat(units).extracting(ParsedUnit::titlePath).containsExactly(
                "退款规范 > 1 退款资格",
                "退款规范 > 1 退款资格 > 1.1 申请主体",
                "退款规范 > 1 退款资格 > 1.1 申请主体");
        assertThat(units).extracting(ParsedUnit::locationLabel)
                .containsExactly("第 1 页", "第 1 页", "第 2 页");
    }

    @Test
    void doesNotTreatNumberedBodyListAsHeading() {
        List<ParsedUnit> units = analyzer.analyze("退款规范.pdf", List.of(layout(1,
                line("退款资格", 18, true, 0.20),
                line("1. 校验订单所有者", 10, false, 0.30),
                line("2. 校验重复申请", 10, false, 0.34))));

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.titlePath()).isEqualTo("退款规范 > 退款资格");
            assertThat(unit.text()).contains("1. 校验订单所有者", "2. 校验重复申请");
        });
    }

    @Test
    void keepsRepeatedMarginsOnlyInRawText() {
        List<ParsedUnit> units = analyzer.analyze("物流规范.pdf", List.of(
                layout(1, line("内部资料", 10, false, 0.05), line("第一条正文。", 10, false, 0.30)),
                layout(2, line("内部资料", 10, false, 0.05), line("第二条正文。", 10, false, 0.30)),
                layout(3, line("内部资料", 10, false, 0.05), line("第三条正文。", 10, false, 0.30))));

        assertThat(units).hasSize(3).allSatisfy(unit -> {
            assertThat(unit.rawText()).contains("内部资料");
            assertThat(unit.text()).doesNotContain("内部资料");
        });
    }

    @Test
    void limitsTitlePathButPreservesNearestHeading() {
        String filename = "根".repeat(1100) + ".pdf";

        ParsedUnit unit = analyzer.analyze(filename, List.of(layout(1,
                line("最近标题", 18, true, 0.20),
                line("正文。", 10, false, 0.30)))).getFirst();

        assertThat(unit.titlePath()).hasSizeLessThanOrEqualTo(1000).endsWith("最近标题");
    }

    @Test
    void doesNotInferOcrHeadingFromBoxHeightAlone() {
        List<PdfTextLine> lines = List.of(
                line("Important note", 18, false, 0.005),
                line("Scanned body text.", 10, false, 0.025));
        PdfPageLayout ocrPage = new PdfPageLayout(
                1, 700, 1000, "Important note\nScanned body text.", lines, 0.88D, false);

        List<ParsedUnit> units = analyzer.analyze("scan.pdf", List.of(ocrPage));

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.titlePath()).isEqualTo("scan");
            assertThat(unit.text()).contains("Important note", "Scanned body text.");
        });
    }

    @Test
    void doesNotTreatBoldBodySizedTableHeaderAsHeading() {
        List<ParsedUnit> units = analyzer.analyze("订单规范.pdf", List.of(layout(1,
                line("1 订单边界", 17, true, 0.10),
                line("本节说明订单事实边界。", 9.4, false, 0.18),
                line("场景或对象 判断条件 处理要求", 7.8, true, 0.32),
                line("主订单 交易汇总 不得作为拣货单", 7.6, false, 0.37),
                line("子订单 仓库履约 必须关联主订单", 7.6, false, 0.42))));

        assertThat(units).singleElement().satisfies(unit -> {
            assertThat(unit.titlePath()).isEqualTo("订单规范 > 1 订单边界");
            assertThat(unit.text()).contains("场景或对象 判断条件 处理要求", "主订单 交易汇总");
        });
    }

    @Test
    void keepsTableOfContentsInOneUnitWithoutPollutingFollowingPage() {
        List<ParsedUnit> units = analyzer.analyze("订单规范.pdf", List.of(
                layout(1,
                        line("目录", 17, true, 0.10),
                        line("目录用于展示章节结构。", 7.8, false, 0.18),
                        line("1 适用范围与订单边界", 10, false, 0.30),
                        line("2 订单创建与幂等控制", 10, false, 0.36),
                        line("3 价格计算与优惠分摊", 10, false, 0.42),
                        line("4 库存预占、确认与释放", 10, false, 0.48)),
                layout(2,
                        line("本页是没有新章节标题的正文。", 9.4, false, 0.20))));

        assertThat(units).hasSize(2);
        assertThat(units.getFirst().titlePath()).isEqualTo("订单规范 > 目录");
        assertThat(units.getFirst().text()).contains("1 适用范围与订单边界", "4 库存预占、确认与释放");
        assertThat(units.getLast().titlePath()).isEqualTo("订单规范");
    }

    @Test
    void doesNotRepeatFilenameRootWhenCoverHeadingHasSameText() {
        ParsedUnit unit = analyzer.analyze("订单规范.pdf", List.of(layout(1,
                line("订单规范", 25, true, 0.20),
                line("订单创建、支付与履约", 13, false, 0.30)))).getFirst();

        assertThat(unit.titlePath()).isEqualTo("订单规范");
    }

    private PdfPageLayout layout(int pageNumber, PdfTextLine... lines) {
        List<PdfTextLine> pageLines = Arrays.asList(lines);
        return new PdfPageLayout(pageNumber, 700, 1000,
                String.join("\n", pageLines.stream().map(PdfTextLine::text).toList()),
                pageLines, null, false);
    }

    private PdfTextLine line(String text, double fontSize, boolean bold, double yRatio) {
        double width = Math.min(500D, Math.max(80D, text.length() * fontSize));
        return new PdfTextLine(text, 60, yRatio * 1000D, width, fontSize,
                fontSize, bold ? "Helvetica-Bold" : "Helvetica",
                bold, 700, 1000);
    }
}
