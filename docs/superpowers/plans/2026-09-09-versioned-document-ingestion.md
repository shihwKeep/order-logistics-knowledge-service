# Versioned Document Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知识库服务增加安全的文件上传、不可变文档版本、MinIO 原件存储、可恢复解析/OCR任务、结构化预览、人工校正和分块前置能力。

**Architecture:** MySQL 保存文档、版本、原文单元、Chunk、任务、Outbox 和审计，是状态真相；MinIO 只保存租户隔离的原始文件和解析产物；RabbitMQ 只做低延迟唤醒，定时扫描仍可恢复任务。解析器通过统一 SPI 按文件类型选择，PaddleOCR 通过独立 HTTP 契约调用，Worker 持有数据库租约并按幂等阶段推进版本。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway、MinIO Java SDK、Spring AMQP、Apache PDFBox、Apache POI、Jsoup、Commons CSV、JUnit 5、Mockito、Testcontainers MySQL。

---

## 文件结构

- `src/main/resources/db/migration/V2__create_document_ingestion.sql`：文档、版本、单元、Chunk、任务与 Outbox 表。
- `src/main/java/com/xjjk/knowledge/document/domain/*`：文档版本、处理状态、原文单元和任务领域模型。
- `src/main/java/com/xjjk/knowledge/document/persistence/*`：租户安全的 MyBatis Mapper 与仓储。
- `src/main/java/com/xjjk/knowledge/document/storage/*`：对象键生成、MinIO 读写和文件校验。
- `src/main/java/com/xjjk/knowledge/document/parser/*`：解析 SPI、格式路由与格式专用解析器。
- `src/main/java/com/xjjk/knowledge/document/ocr/*`：PaddleOCR HTTP 契约和低置信度标记。
- `src/main/java/com/xjjk/knowledge/document/task/*`：Outbox 发布、任务租约、Worker 和失败恢复。
- `src/main/java/com/xjjk/knowledge/document/service/*`：上传、查询、预览、校正和重试应用服务。
- `src/main/java/com/xjjk/knowledge/document/web/*`：租户路径下的管理 API。
- `ocr-service/*`：独立 FastAPI PaddleOCR 进程及容器定义。

### Task 1: 文档入库数据库模型

**Files:**
- Create: `src/main/resources/db/migration/V2__create_document_ingestion.sql`
- Create: `src/test/java/com/xjjk/knowledge/persistence/DocumentIngestionMigrationTest.java`

- [ ] **Step 1: 写失败的迁移测试**

测试在 MySQL 8.4 Testcontainer 中执行 V1、V2，并断言以下表存在：

```java
assertThat(tableNames).contains(
        "kb_document",
        "kb_document_version",
        "kb_document_unit",
        "kb_chunk",
        "kb_ingestion_task",
        "kb_outbox_event");
```

- [ ] **Step 2: 确认测试因 V2 缺失而失败**

Run: `mvn -Dtest=DocumentIngestionMigrationTest test`

Expected: FAIL，缺少 `kb_document`。

- [ ] **Step 3: 创建 V2 迁移**

迁移必须包含：

```sql
CREATE TABLE kb_document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(255) NOT NULL,
  current_draft_version_id BIGINT UNSIGNED NULL,
  current_published_version_id BIGINT UNSIGNED NULL,
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  row_version INT NOT NULL DEFAULT 0,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_document_tenant_kb (tenant_id, knowledge_base_id, is_deleted)
);
```

`kb_document_version` 保存 `status`、`source_object_key`、`source_sha256`、`mime_type`、`file_size`、`parser_version`、`ocr_required`、`correction_revision` 和错误字段；`kb_document_unit` 保存页/幻灯片/工作表定位、原文、校正文、OCR 置信度；`kb_chunk` 保存位置、正文、Token 数和哈希；任务表包含 `stage/status/retry_count/next_run_at/lease_token/locked_by/locked_until`；Outbox 包含聚合、事件类型、载荷、尝试次数和投递时间。

- [ ] **Step 4: 运行迁移测试**

Run: `mvn -Dtest=DocumentIngestionMigrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/db/migration/V2__create_document_ingestion.sql src/test/java/com/xjjk/knowledge/persistence/DocumentIngestionMigrationTest.java
git commit -m "feat: add versioned document schema"
```

### Task 2: 文档与版本持久层

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/domain/DocumentStatus.java`
- Create: `src/main/java/com/xjjk/knowledge/document/domain/KnowledgeDocument.java`
- Create: `src/main/java/com/xjjk/knowledge/document/domain/DocumentVersion.java`
- Create: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/document/persistence/MybatisDocumentRepository.java`
- Create: `src/test/java/com/xjjk/knowledge/document/persistence/DocumentRepositoryIntegrationTest.java`

- [ ] **Step 1: 写失败的租户隔离测试**

```java
assertThat(repository.findDocument(1L, documentId)).isPresent();
assertThat(repository.findDocument(2L, documentId)).isEmpty();
assertThat(repository.listDocuments(2L, knowledgeBaseId)).isEmpty();
```

测试还需断言新上传版本会原子推进 `current_draft_version_id`，不会修改 `current_published_version_id`。

- [ ] **Step 2: 确认因仓储不存在而失败**

Run: `mvn -Dtest=DocumentRepositoryIntegrationTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现最小持久层**

所有查询和更新条件都同时包含 `tenant_id`、资源主键和 `is_deleted=0`。仓储接口使用领域对象，不向应用服务泄漏 MyBatis Entity：

```java
public interface DocumentRepository {
    CreatedDocument createDraft(
            long tenantId,
            long knowledgeBaseId,
            long actorUserId,
            String title,
            SourceFile source);
    Optional<KnowledgeDocument> findDocument(long tenantId, long documentId);
    Optional<DocumentVersion> findVersion(long tenantId, long documentId, long versionId);
    List<KnowledgeDocument> listDocuments(long tenantId, long knowledgeBaseId);
}
```

- [ ] **Step 4: 运行仓储测试**

Run: `mvn -Dtest=DocumentRepositoryIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/xjjk/knowledge/document src/test/java/com/xjjk/knowledge/document
git commit -m "feat: persist versioned knowledge documents"
```

### Task 3: 安全上传与 MinIO 对象存储

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/DocumentStorageProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/SourceObjectStore.java`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/MinioSourceObjectStore.java`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/DocumentObjectKey.java`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/UploadPolicy.java`
- Create: `src/test/java/com/xjjk/knowledge/document/storage/DocumentObjectKeyTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/storage/UploadPolicyTest.java`

- [ ] **Step 1: 写失败的对象键和上传校验测试**

```java
assertThat(DocumentObjectKey.source(3L, 8L, 13L, 21L))
        .isEqualTo("tenant/3/knowledge-base/8/document/13/version/21/source");
assertThatThrownBy(() -> policy.validate("evil.exe", "application/pdf", pdfBytes))
        .isInstanceOf(BusinessException.class);
```

覆盖双扩展名、空文件、超过配置大小、MIME 与文件签名冲突，以及 PDF/DOCX/XLS/XLSX/CSV/PPTX/TXT/MD/HTML/PNG/JPEG 白名单。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=DocumentObjectKeyTest,UploadPolicyTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 引入 MinIO 并实现存储边界**

`SourceObjectStore` 只暴露受控对象键：

```java
public interface SourceObjectStore {
    void put(String objectKey, InputStream input, long size, String contentType);
    InputStream get(String objectKey);
    void putParsed(String objectKey, byte[] content, String contentType);
}
```

文件名不得拼入对象键；上传策略根据扩展名、声明 MIME 和 magic bytes 三者校验；日志不得输出正文和对象存储凭据。

- [ ] **Step 4: 运行存储单元测试**

Run: `mvn -Dtest=DocumentObjectKeyTest,UploadPolicyTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/java/com/xjjk/knowledge/document/storage src/test/java/com/xjjk/knowledge/document/storage
git commit -m "feat: add secure minio source storage"
```

### Task 4: 上传应用服务与管理 API

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`
- Modify: `src/main/java/com/xjjk/knowledge/audit/AuditAction.java`
- Create: `src/main/java/com/xjjk/knowledge/document/service/DocumentUploadService.java`
- Create: `src/main/java/com/xjjk/knowledge/document/web/DocumentController.java`
- Create: `src/main/java/com/xjjk/knowledge/document/web/dto/DocumentResponse.java`
- Create: `src/test/java/com/xjjk/knowledge/document/service/DocumentUploadServiceTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/web/DocumentControllerTest.java`

- [ ] **Step 1: 写失败的上传服务测试**

验证顺序为：租户授权 → 知识库存在 → 文件校验 → MinIO 上传 → MySQL 短事务登记文档/版本/任务/Outbox → 审计。对象存储失败时数据库不能出现版本；数据库登记失败时返回稳定错误码并保留可追踪的孤儿清理信息。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=DocumentUploadServiceTest,DocumentControllerTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现上传接口**

```text
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents
Content-Type: multipart/form-data
Parts: file, title(optional)
```

成功返回文档、草稿版本、处理状态和创建时间；写操作沿用 CSRF、`X-Request-Id`、管理员角色和租户校验。

- [ ] **Step 4: 运行上传测试**

Run: `mvn -Dtest=DocumentUploadServiceTest,DocumentControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/xjjk/knowledge/common src/main/java/com/xjjk/knowledge/audit src/main/java/com/xjjk/knowledge/document/service src/main/java/com/xjjk/knowledge/document/web src/test/java/com/xjjk/knowledge/document
git commit -m "feat: expose tenant safe document upload"
```

### Task 5: 统一解析 SPI 与文本类格式

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/DocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/DocumentParserRegistry.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/ParsedDocument.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/ParsedUnit.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/TextDocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/HtmlDocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/CsvDocumentParser.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/TextDocumentParserTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/HtmlDocumentParserTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/CsvDocumentParserTest.java`

- [ ] **Step 1: 写失败的解析测试**

断言 UTF-8/GB18030 文本可识别；HTML 删除 script/style/nav 并保留标题层级；CSV 每个逻辑表格保留表头，单元格中的换行和引号不损坏。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=TextDocumentParserTest,HtmlDocumentParserTest,CsvDocumentParserTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现解析 SPI**

```java
public interface DocumentParser {
    boolean supports(String extension, String mimeType);
    ParsedDocument parse(ParseRequest request);
    String version();
}
```

解析结果由 `ParsedUnit(unitType, unitIndex, locationLabel, titlePath, text, ocrConfidence)` 组成，不允许解析器直接写数据库。

- [ ] **Step 4: 运行解析测试**

Run: `mvn -Dtest=TextDocumentParserTest,HtmlDocumentParserTest,CsvDocumentParserTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/java/com/xjjk/knowledge/document/parser src/test/java/com/xjjk/knowledge/document/parser
git commit -m "feat: parse text html and csv documents"
```

### Task 6: Office 文档解析

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/parser/DocxDocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/SpreadsheetDocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PptxDocumentParser.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/OfficeDocumentParserTest.java`
- Create: `src/test/resources/documents/sample.docx`
- Create: `src/test/resources/documents/sample.xls`
- Create: `src/test/resources/documents/sample.xlsx`
- Create: `src/test/resources/documents/sample.pptx`

- [ ] **Step 1: 写失败的 Office 样本测试**

DOCX 断言标题、段落、列表和表格顺序；XLS/XLSX 断言工作表名、表头和公式缓存值；PPTX 断言幻灯片编号、标题、正文、表格和备注。嵌入图片被登记为 OCR 区域，不能静默丢弃。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=OfficeDocumentParserTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 使用 Apache POI 实现格式专用解析器**

每张工作表和每页幻灯片至少形成一个可定位单元；表格输出重复表头；解析限制使用 `maxSheets`、`maxSlides`、`maxRowsPerSheet`，超限返回 `DOCUMENT_LIMIT_EXCEEDED`。

- [ ] **Step 4: 运行 Office 解析测试**

Run: `mvn -Dtest=OfficeDocumentParserTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/xjjk/knowledge/document/parser src/test/java/com/xjjk/knowledge/document/parser src/test/resources/documents
git commit -m "feat: parse office knowledge documents"
```

### Task 7: PDF、图片与独立 PaddleOCR 契约

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/xjjk/knowledge/document/ocr/OcrClient.java`
- Create: `src/main/java/com/xjjk/knowledge/document/ocr/PaddleOcrHttpClient.java`
- Create: `src/main/java/com/xjjk/knowledge/document/ocr/OcrProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/PdfDocumentParser.java`
- Create: `src/main/java/com/xjjk/knowledge/document/parser/ImageDocumentParser.java`
- Create: `src/test/java/com/xjjk/knowledge/document/ocr/PaddleOcrHttpClientTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/parser/PdfAndImageParserTest.java`
- Create: `ocr-service/app/main.py`
- Create: `ocr-service/requirements.txt`
- Create: `ocr-service/Dockerfile`
- Create: `ocr-service/tests/test_api.py`

- [ ] **Step 1: 写失败的 PDF/OCR 契约测试**

文本 PDF 直接提取文字；无文本或文字密度低的页渲染为 PNG 后调用 OCR；图片直接 OCR；旋转角度、文本框和平均置信度映射到原文单元；任一必需页面 OCR 失败时版本失败。

HTTP 契约：

```json
{
  "requestId": "uuid",
  "language": "ch",
  "imageBase64": "..."
}
```

```json
{
  "requestId": "uuid",
  "rotation": 0,
  "blocks": [{"text":"退款规则","confidence":0.98,"box":[[0,0],[1,0],[1,1],[0,1]]}]
}
```

- [ ] **Step 2: 确认 Java 和 Python 测试失败**

Run: `mvn -Dtest=PaddleOcrHttpClientTest,PdfAndImageParserTest test`

Expected: FAIL 编译错误。

Run: `python -m unittest discover -s ocr-service/tests -v`

Expected: FAIL，API 模块不存在。

- [ ] **Step 3: 实现 Java 客户端和独立 OCR 服务**

Java 客户端设置连接、读取和最大响应限制，不记录图片/Base64；Python 服务限制图片大小和像素数，使用 PaddleOCR 单例模型，响应稳定错误码。低于配置阈值的块保留正文并标记 `lowConfidence=true`。

- [ ] **Step 4: 运行 OCR 契约测试**

Run: `mvn -Dtest=PaddleOcrHttpClientTest,PdfAndImageParserTest test`

Expected: PASS。

Run: `python -m unittest discover -s ocr-service/tests -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/java/com/xjjk/knowledge/document/ocr src/main/java/com/xjjk/knowledge/document/parser src/test/java/com/xjjk/knowledge/document/ocr src/test/java/com/xjjk/knowledge/document/parser ocr-service
git commit -m "feat: add pdf and paddle ocr parsing"
```

### Task 8: 规范化、结构分块与解析产物

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/processing/TextNormalizer.java`
- Create: `src/main/java/com/xjjk/knowledge/document/processing/StructuralChunker.java`
- Create: `src/main/java/com/xjjk/knowledge/document/processing/ChunkingProperties.java`
- Create: `src/test/java/com/xjjk/knowledge/document/processing/TextNormalizerTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/processing/StructuralChunkerTest.java`

- [ ] **Step 1: 写失败的规范化和分块测试**

断言标题路径会进入 Chunk；段落、列表和表格边界优先；表格拆分后重复表头；订单号和政策编号不从中间切断；目标 500、最大 800、重叠 80 Token 均从配置读取；相同正文产生稳定 SHA-256。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=TextNormalizerTest,StructuralChunkerTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现纯函数处理组件**

Chunk 身份使用：

```text
tenantId + documentId + versionId + chunkIndex
```

Token 估算器作为接口注入，当前实现采用与后续 Qwen3 Embedding 一致的保守估算策略；原始文本、规范化文本和 Chunk 分开保存。

- [ ] **Step 4: 运行处理测试**

Run: `mvn -Dtest=TextNormalizerTest,StructuralChunkerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/xjjk/knowledge/document/processing src/test/java/com/xjjk/knowledge/document/processing
git commit -m "feat: normalize and chunk parsed documents"
```

### Task 9: Outbox、RabbitMQ 唤醒与数据库任务租约

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/xjjk/knowledge/document/task/IngestionTaskRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/document/task/OutboxPublisher.java`
- Create: `src/main/java/com/xjjk/knowledge/document/task/IngestionWakeupListener.java`
- Create: `src/main/java/com/xjjk/knowledge/document/task/IngestionWorker.java`
- Create: `src/main/java/com/xjjk/knowledge/document/task/IngestionTaskScanner.java`
- Create: `src/main/java/com/xjjk/knowledge/document/task/IngestionProperties.java`
- Create: `src/test/java/com/xjjk/knowledge/document/task/IngestionTaskRepositoryIntegrationTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/task/IngestionWorkerTest.java`

- [ ] **Step 1: 写失败的租约与恢复测试**

两个 Worker 同时领取时只有一个成功；过期租约可被重新领取；持有者使用 `lease_token` 完成任务；错误按指数退避进入 `RETRY`；超过最大次数进入 `DEAD`；RabbitMQ 未投递时定时扫描仍能处理任务。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=IngestionTaskRepositoryIntegrationTest,IngestionWorkerTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现任务协调**

领取使用数据库条件更新，不能仅依赖 JVM 锁：

```sql
UPDATE kb_ingestion_task
SET status='PROCESSING', lease_token=?, locked_by=?, locked_until=?
WHERE id=? AND status IN ('PENDING','RETRY')
  AND next_run_at<=CURRENT_TIMESTAMP(3)
  AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3));
```

Worker 完成解析、规范化和分块后将版本推进到 `INDEXING`，等待第三阶段索引；每一步按版本和单元序号幂等覆盖派生数据。

- [ ] **Step 4: 运行任务测试**

Run: `mvn -Dtest=IngestionTaskRepositoryIntegrationTest,IngestionWorkerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pom.xml src/main/java/com/xjjk/knowledge/document/task src/test/java/com/xjjk/knowledge/document/task
git commit -m "feat: process ingestion tasks with leases"
```

### Task 10: 文档查询、预览、失败重试与人工校正

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/document/service/DocumentQueryService.java`
- Create: `src/main/java/com/xjjk/knowledge/document/service/DocumentCorrectionService.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/web/DocumentController.java`
- Create: `src/main/java/com/xjjk/knowledge/document/web/dto/DocumentDetailResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/document/web/dto/DocumentUnitResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/document/web/dto/CorrectDocumentUnitRequest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/service/DocumentCorrectionServiceTest.java`
- Create: `src/test/java/com/xjjk/knowledge/document/web/DocumentQueryControllerTest.java`

- [ ] **Step 1: 写失败的查询和校正测试**

覆盖文档列表、版本列表、任务状态、分页原文单元、低置信度过滤、失败任务手动重试；校正不得覆盖 `raw_text`，必须更新 `corrected_text`、递增 `correction_revision`、清除旧 Chunk 并登记新的处理任务和审计。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=DocumentCorrectionServiceTest,DocumentQueryControllerTest test`

Expected: FAIL 编译错误。

- [ ] **Step 3: 实现管理 API**

```text
GET  .../documents
GET  .../documents/{documentId}
GET  .../documents/{documentId}/versions
GET  .../documents/{documentId}/versions/{versionId}/units
PUT  .../documents/{documentId}/versions/{versionId}/units/{unitId}/correction
POST .../documents/{documentId}/versions/{versionId}/retry
```

所有路径都通过 `tenantId + knowledgeBaseId + documentId + versionId` 完整校验归属；返回对象键和内部异常堆栈必须被隐藏。

- [ ] **Step 4: 运行查询校正测试**

Run: `mvn -Dtest=DocumentCorrectionServiceTest,DocumentQueryControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/xjjk/knowledge/document/service src/main/java/com/xjjk/knowledge/document/web src/test/java/com/xjjk/knowledge/document
git commit -m "feat: preview and correct parsed documents"
```

### Task 11: 本地基础设施、配置与运行手册

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Create: `compose.knowledge.yml`
- Modify: `docs/local-foundation-runbook.md`
- Create: `docs/versioned-ingestion-runbook.md`
- Modify: `src/test/java/com/xjjk/knowledge/config/ConfigurationContractTest.java`

- [ ] **Step 1: 写失败的配置契约测试**

断言 MinIO、RabbitMQ、OCR、上传限制、解析限制、租约、重试和分块参数存在，并且密码、密钥没有硬编码真实值。

- [ ] **Step 2: 确认测试失败**

Run: `mvn -Dtest=ConfigurationContractTest test`

Expected: FAIL，缺少 `knowledge.document.storage.endpoint`。

- [ ] **Step 3: 增加本地运行定义和手册**

Compose 仅包含独立 MinIO、RabbitMQ 和 PaddleOCR；不覆盖用户现有 MySQL、Redis、Elasticsearch 或 Milvus。敏感配置只使用环境变量占位符。手册给出启动顺序、健康检查、上传、查看进度、预览、校正、重试和故障诊断命令。

- [ ] **Step 4: 运行配置测试和完整测试**

Run: `mvn test`

Expected: 全部测试 PASS。

Run: `python -m unittest discover -s ocr-service/tests -v`

Expected: 全部测试 PASS。

- [ ] **Step 5: 构建并执行敏感信息扫描**

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS。

Run: `rg -n "client-secret:\\s+[^$]|password:\\s+[^$]|AKIA[0-9A-Z]{16}" src docs compose.knowledge.yml ocr-service`

Expected: 无真实凭据命中。

- [ ] **Step 6: 提交**

```bash
git add src/main/resources src/test/java/com/xjjk/knowledge/config compose.knowledge.yml docs ocr-service
git commit -m "docs: add versioned ingestion operations"
```

### Task 12: 需求回归与分支交付

**Files:**
- Verify: all changed files

- [ ] **Step 1: 对照设计逐项核验**

确认支持 PDF、扫描 PDF、DOCX、XLS/XLSX、CSV、PPTX、TXT、Markdown、HTML、PNG/JPEG；确认租户隔离、不可变版本、OCR 逐页失败、低置信度提示、人工校正、任务恢复和审计均有自动化测试。

- [ ] **Step 2: 运行完整 Java 与 OCR 测试**

Run: `mvn clean test`

Expected: BUILD SUCCESS，0 failures，0 errors。

Run: `python -m unittest discover -s ocr-service/tests -v`

Expected: OK。

- [ ] **Step 3: 确认工作区只包含计划内变更**

Run: `git status --short && git diff --check && git log --oneline --decorate -15`

Expected: 工作区干净；无空白错误；提交均属于版本化文档入库阶段。

- [ ] **Step 4: 推送功能分支**

Run: `git push -u origin feature/versioned-document-ingestion`

Expected: 远端分支创建成功。

