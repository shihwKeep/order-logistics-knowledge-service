package com.xjjk.knowledge.document.storage;

import com.xjjk.knowledge.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPolicyTest {

    private final UploadPolicy policy = new UploadPolicy(1024 * 1024);

    @Test
    void acceptsSupportedSignaturesAndCalculatesStableDigest() throws Exception {
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        ValidatedUpload validated = policy.validate("退款规则.pdf", "application/pdf", pdf);

        assertThat(validated.extension()).isEqualTo("pdf");
        assertThat(validated.mimeType()).isEqualTo("application/pdf");
        assertThat(validated.sha256()).hasSize(64);

        assertThat(policy.validate("政策.docx", null, officeZip("word/document.xml"))
                .extension()).isEqualTo("docx");
        assertThat(policy.validate("数据.xlsx", null, officeZip("xl/workbook.xml"))
                .extension()).isEqualTo("xlsx");
        assertThat(policy.validate("培训.pptx", null, officeZip("ppt/presentation.xml"))
                .extension()).isEqualTo("pptx");
    }

    @Test
    void acceptsTextAndImageFormats() {
        assertThat(policy.validate("规则.txt", "text/plain", "中文".getBytes(StandardCharsets.UTF_8))
                .extension()).isEqualTo("txt");
        assertThat(policy.validate("规则.md", "text/markdown", "# 标题".getBytes(StandardCharsets.UTF_8))
                .extension()).isEqualTo("md");
        assertThat(policy.validate("规则.csv", "text/csv", "编号,说明".getBytes(StandardCharsets.UTF_8))
                .extension()).isEqualTo("csv");
        assertThat(policy.validate("规则.html", "text/html", "<h1>规则</h1>".getBytes(StandardCharsets.UTF_8))
                .extension()).isEqualTo("html");
        assertThat(policy.validate("图片.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})
                .extension()).isEqualTo("png");
        assertThat(policy.validate("图片.jpg", "image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01})
                .extension()).isEqualTo("jpg");
    }

    @Test
    void rejectsEmptyOversizedExecutableAndMismatchedFiles() {
        assertThatThrownBy(() -> policy.validate("empty.txt", "text/plain", new byte[0]))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new UploadPolicy(3).validate(
                "large.txt", "text/plain", new byte[]{1, 2, 3, 4}))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate(
                "evil.exe.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate(
                "fake.pdf", "application/pdf", "not-pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validate(
                "fake.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "PKfake".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(BusinessException.class);
    }

    private byte[] officeZip(String marker) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(marker));
            zip.write("content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
