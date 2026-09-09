package com.xjjk.knowledge.document.parser;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import com.xjjk.knowledge.document.ocr.OcrBlock;
import com.xjjk.knowledge.document.ocr.OcrResult;
import java.util.List;

class OfficeDocumentParserTest {

    @Test
    void parsesDocxHeadingsParagraphsAndTablesInOrder() throws Exception {
        byte[] document = docx();

        ParsedDocument parsed = new DocxDocumentParser(1000).parse(new ParseRequest(
                "售后规则.docx", "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                document));

        assertThat(parsed.units()).extracting(ParsedUnit::text)
                .containsExactly("签收后七日内可以申请退款。", "类型 | 时限\n退款 | 7天");
        assertThat(parsed.units()).extracting(ParsedUnit::titlePath)
                .containsOnly("售后规则");
    }

    @Test
    void parsesXlsAndXlsxWithSheetAndRepeatedHeader() throws Exception {
        for (Workbook workbook : new Workbook[]{new HSSFWorkbook(), new XSSFWorkbook()}) {
            byte[] content;
            try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("订单状态");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue("编码");
                header.createCell(1).setCellValue("说明");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("WAIT_SEND");
                row.createCell(1).setCellValue("待发货");
                workbook.write(output);
                content = output.toByteArray();
            }

            String extension = content[0] == (byte) 0xd0 ? "xls" : "xlsx";
            ParsedDocument parsed = new SpreadsheetDocumentParser(10, 1000)
                    .parse(new ParseRequest("订单." + extension, extension, null, content));

            assertThat(parsed.units()).hasSize(1);
            assertThat(parsed.units().getFirst().locationLabel()).isEqualTo("工作表 订单状态 第 2 行");
            assertThat(parsed.units().getFirst().text())
                    .isEqualTo("编码 | 说明\nWAIT_SEND | 待发货");
        }
    }

    @Test
    void parsesPptxBySlideAndMarksEmbeddedImagesForOcr() throws Exception {
        byte[] presentation;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox title = slide.createTextBox();
            title.setText("物流异常处理");
            XSLFTextBox body = slide.createTextBox();
            body.setText("破损件需要登记照片");
            byte[] image = png();
            var pictureData = show.addPicture(image, PictureData.PictureType.PNG);
            slide.createPicture(pictureData);
            show.write(output);
            presentation = output.toByteArray();
        }

        ParsedDocument parsed = new PptxDocumentParser(
                (requestId, language, image) -> new OcrResult(
                        requestId, 0, List.of(new OcrBlock("图片中的签收规范", 0.93, List.of(), false))),
                100).parse(new ParseRequest(
                "培训.pptx", "pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                presentation));

        assertThat(parsed.units()).hasSize(1);
        assertThat(parsed.units().getFirst().locationLabel()).isEqualTo("幻灯片 1");
        assertThat(parsed.units().getFirst().text())
                .contains("物流异常处理", "破损件需要登记照片", "图片中的签收规范");
        assertThat(parsed.ocrRequired()).isTrue();
    }

    private byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("售后规则");
            document.createParagraph().createRun().setText("签收后七日内可以申请退款。");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("类型");
            table.getRow(0).getCell(1).setText("时限");
            table.getRow(1).getCell(0).setText("退款");
            table.getRow(1).getCell(1).setText("7天");
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.WHITE.getRGB());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
