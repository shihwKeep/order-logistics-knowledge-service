# Knowledge Document Version and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知识库补齐同一文档的 v2/v3 上传、草稿安全切换、单文档与多文档 Release 原子发布、历史回滚、索引安全清理和管理端完整操作界面。

**Architecture:** MySQL 保存文档版本、任务和活动 Release，是唯一事实源；MinIO、Elasticsearch 与 Milvus 都是可恢复的外部存储或派生索引。上传和发布先登记数据库任务与 Outbox，再由有租约的 Worker 执行外部工作；草稿通过文档指针终审，线上结果通过知识库 `current_release_id` 对应的完整 Release 清单终审。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis、Flyway、MySQL 8、RabbitMQ、MinIO、Elasticsearch 8、Milvus、JUnit 5、Testcontainers、Vue 3、TypeScript、Vitest。

**Execution constraint:** 按用户要求直接在两个本地 `main` 分支实施，不创建代理任务；后端已有的 `src/main/resources/application-local.yml` 本地改动必须保留且不得提交。

---

## 文件结构与职责

后端仓库：`D:/GitCode/order-logistics-knowledge-service`

- `src/main/resources/db/migration/V6__create_document_versions_and_releases.sql`：版本幂等、Release、Release Task、通用清理任务和存量发布指针迁移。
- `document/persistence/*`：创建新文档 v1 或已有文档后续版本，负责行锁、去重与版本号递增。
- `document/service/DocumentUploadService.java`：上传编排和 MinIO/事务一致性边界。
- `document/task/*`：READY 原子推进、草稿指针切换和失败清理登记。
- `publication/release/*`：Release 清单、仓储、任务租约、Worker、控制器和 DTO；Release 领域单独成包，避免继续膨胀旧 `PublicationService`。
- `publication/cleanup/*`：草稿/正式索引通用清理与 MinIO 孤儿对象扫描。
- `retrieval/service/PublishedVersionMapper.java`：活动 Release 终审。
- `common/api/ApiErrorCode.java`：稳定错误码。

前端仓库：`D:/GitCode/order-logistics-agent-admin-web`

- `src/api/http.ts`：一次用户动作在 CSRF 重试中复用同一 `X-Request-Id`。
- `src/api/types.ts`、`src/api/knowledge.ts`：文档版本和 Release API 契约。
- `src/views/DocumentsView.vue`：线上/草稿/处理中状态、勾选和批量发布。
- `src/views/DocumentDetailView.vue`：上传新版本和单文档发布兼容入口。
- `src/views/ReleaseHistoryView.vue`：Release 历史、明细和回滚。
- `src/router.ts`、`src/styles.css`：Release 页面路由与界面样式。

---

### Task 1: 建立数据库版本、Release 和迁移契约

**Files:**
- Create: `src/main/resources/db/migration/V6__create_document_versions_and_releases.sql`
- Create: `src/test/java/com/xjjk/knowledge/persistence/KnowledgeReleaseMigrationTest.java`

- [ ] **Step 1: 编写失败的 Flyway 迁移测试**

测试启动空库并迁移到 V6，然后断言以下契约：

```java
@Test
void migratesDocumentVersionAndReleaseSchema() throws Exception {
    assertColumn("kb_document_version", "upload_request_id", false);
    assertColumn("kb_knowledge_base", "current_release_id", true);
    assertUniqueKey("kb_document_version", "uk_version_upload_request");
    assertUniqueKey("kb_release", "uk_release_request");
    assertUniqueKey("kb_release_item", "uk_release_document");
    assertTable("kb_release_task");
    assertTable("kb_derived_index_cleanup");
}

@Test
void backfillsExistingPublishedPointersIntoInitialRelease() {
    seedKnowledgeBaseWithTwoPublishedDocuments();
    migrateToLatest();
    Long releaseId = jdbc.queryForObject(
            "SELECT current_release_id FROM kb_knowledge_base WHERE id=1", Long.class);
    assertThat(releaseId).isNotNull();
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM kb_release_item WHERE release_id=?", Long.class, releaseId))
            .isEqualTo(2L);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -Dtest=KnowledgeReleaseMigrationTest test
```

Expected: FAIL，原因是 V6、Release 表和新字段尚不存在。

- [ ] **Step 3: 编写 V6 迁移**

迁移必须按下面顺序执行，避免存量数据上的 `NOT NULL` 失败：

```sql
ALTER TABLE kb_document_version ADD COLUMN upload_request_id VARCHAR(64) NULL AFTER source_sha256;
UPDATE kb_document_version SET upload_request_id=CONCAT('legacy-version-', id)
 WHERE upload_request_id IS NULL;
ALTER TABLE kb_document_version
  MODIFY upload_request_id VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_version_upload_request (tenant_id, upload_request_id);

CREATE TABLE kb_release (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  release_number INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  base_release_id BIGINT UNSIGNED NULL,
  request_id VARCHAR(64) NOT NULL,
  manifest_sha256 CHAR(64) NOT NULL,
  base_row_version INT NOT NULL,
  created_by BIGINT NOT NULL,
  failure_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  activated_at DATETIME(3) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_number (tenant_id, knowledge_base_id, release_number),
  UNIQUE KEY uk_release_request (tenant_id, request_id),
  KEY idx_release_kb_status (tenant_id, knowledge_base_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE kb_release_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  release_id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  content_manifest_sha256 CHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_document (release_id, document_id),
  KEY idx_release_item_version (tenant_id, knowledge_base_id, document_id, version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE kb_release_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  release_id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_token CHAR(36) NULL,
  locked_by VARCHAR(100) NULL,
  locked_until DATETIME(3) NULL,
  last_error_code VARCHAR(64) NULL,
  last_error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_release_task (release_id),
  KEY idx_release_task_claim (status, next_run_at, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE kb_derived_index_cleanup (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  index_layer VARCHAR(20) NOT NULL,
  cleanup_reason VARCHAR(32) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_token CHAR(36) NULL,
  locked_by VARCHAR(100) NULL,
  locked_until DATETIME(3) NULL,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_target (tenant_id, document_id, version_id, index_layer, cleanup_reason),
  KEY idx_cleanup_claim (status, next_run_at, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE kb_knowledge_base ADD COLUMN current_release_id BIGINT UNSIGNED NULL AFTER status;

SET SESSION group_concat_max_len = 16777216;
INSERT INTO kb_release
  (tenant_id, knowledge_base_id, release_number, status, base_release_id, request_id,
   manifest_sha256, base_row_version, created_by, created_at, activated_at, updated_at)
SELECT kb.tenant_id, kb.id, 1, 'ACTIVE', NULL, CONCAT('legacy-release-', kb.id),
       SHA2(GROUP_CONCAT(CONCAT(d.id, ':', d.current_published_version_id, ':',
              COALESCE(v.index_manifest_sha256, SHA2(CONCAT('legacy-version-', v.id), 256)))
              ORDER BY d.id SEPARATOR '\n'), 256),
       kb.row_version, kb.updated_by, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
  FROM kb_knowledge_base kb
  JOIN kb_document d ON d.tenant_id=kb.tenant_id AND d.knowledge_base_id=kb.id
                    AND d.is_deleted=0 AND d.current_published_version_id IS NOT NULL
  JOIN kb_document_version v ON v.tenant_id=d.tenant_id AND v.id=d.current_published_version_id
 GROUP BY kb.tenant_id, kb.id, kb.row_version, kb.updated_by;

INSERT INTO kb_release_item
  (release_id, tenant_id, knowledge_base_id, document_id, version_id, content_manifest_sha256)
SELECT r.id, d.tenant_id, d.knowledge_base_id, d.id, d.current_published_version_id,
       COALESCE(v.index_manifest_sha256, SHA2(CONCAT('legacy-version-', v.id), 256))
  FROM kb_release r
  JOIN kb_document d ON d.tenant_id=r.tenant_id AND d.knowledge_base_id=r.knowledge_base_id
                    AND d.is_deleted=0 AND d.current_published_version_id IS NOT NULL
  JOIN kb_document_version v ON v.tenant_id=d.tenant_id AND v.id=d.current_published_version_id
 WHERE r.request_id=CONCAT('legacy-release-', r.knowledge_base_id);

UPDATE kb_knowledge_base kb
JOIN kb_release r ON r.tenant_id=kb.tenant_id AND r.knowledge_base_id=kb.id
                 AND r.request_id=CONCAT('legacy-release-', kb.id)
   SET kb.current_release_id=r.id;

INSERT IGNORE INTO kb_derived_index_cleanup
  (tenant_id, knowledge_base_id, document_id, version_id, index_layer,
   cleanup_reason, status, retry_count, next_run_at, last_error_code, created_at, updated_at)
SELECT old.tenant_id, d.knowledge_base_id, old.document_id, old.version_id, 'PUBLISHED',
       'RELEASE_SUPERSEDED', old.status, old.retry_count, old.next_run_at,
       old.last_error_code, old.created_at, old.updated_at
  FROM kb_published_index_cleanup old
  JOIN kb_document d ON d.tenant_id=old.tenant_id AND d.id=old.document_id;
```

迁移保留旧清理表供旧版本应用短期兼容读取；新代码只写 `kb_derived_index_cleanup`。确认新版本完成一次全量启动和清理扫描后，再在后续独立迁移删除旧表，避免本次部署同时引入破坏性回退障碍。

- [ ] **Step 4: 运行迁移测试确认通过**

Run: `mvn -Dtest=KnowledgeReleaseMigrationTest test`

Expected: PASS，且存量知识库查询到的 Release Item 与原发布指针完全相同。

- [ ] **Step 5: 提交数据库契约**

```powershell
git add src/main/resources/db/migration/V6__create_document_versions_and_releases.sql src/test/java/com/xjjk/knowledge/persistence/KnowledgeReleaseMigrationTest.java
git commit -m "feat: add document release schema"
```

---

### Task 2: 实现新文档与已有文档新版本上传

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/common/api/ApiErrorCode.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/domain/DocumentVersion.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentVersionEntity.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/DocumentRepository.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/persistence/MybatisDocumentRepository.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/service/DocumentUploadService.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/web/DocumentController.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/web/dto/DocumentResponse.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/persistence/DocumentRepositoryIntegrationTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/service/DocumentUploadServiceTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/web/DocumentControllerTest.java`

- [ ] **Step 1: 写失败测试覆盖 v2、幂等、去重和归属校验**

```java
@Test
void createsSecondVersionWithoutCreatingAnotherDocument() {
    CreatedDocument v1 = repository.createDocument(1, 3, 9, "退款规则", source("a"), "req-v1");
    CreatedDocument v2 = repository.createVersion(1, 3, v1.document().id(), 9, source("b"), "req-v2");
    assertThat(v2.document().id()).isEqualTo(v1.document().id());
    assertThat(v2.version().versionNumber()).isEqualTo(2);
    assertThat(repository.listDocuments(1, 3)).hasSize(1);
}

@Test
void returnsSameVersionForSameRequestAndRejectsChangedPayload() {
    CreatedDocument first = uploadVersion("req-2", bytes("second"));
    assertThat(uploadVersion("req-2", bytes("second")).version().id()).isEqualTo(first.version().id());
    assertThatThrownBy(() -> uploadVersion("req-2", bytes("different")))
            .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
}

@Test
void rejectsUnchangedDocumentContent() {
    uploadVersion("req-a", bytes("same"));
    assertThatThrownBy(() -> uploadVersion("req-b", bytes("same")))
            .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.DOCUMENT_CONTENT_UNCHANGED));
}
```

- [ ] **Step 2: 运行目标测试确认失败**

Run:

```powershell
mvn -Dtest=DocumentRepositoryIntegrationTest,DocumentUploadServiceTest,DocumentControllerTest test
```

Expected: FAIL，缺少 `createVersion`、上传幂等字段和新接口。

- [ ] **Step 3: 增加稳定错误码和仓储契约**

```java
IDEMPOTENCY_KEY_REUSED("IDEMPOTENCY_KEY_REUSED", "请求号已用于其他上传内容", HttpStatus.CONFLICT),
DOCUMENT_CONTENT_UNCHANGED("DOCUMENT_CONTENT_UNCHANGED", "文件内容与已有版本相同，无需重复上传", HttpStatus.CONFLICT),
RELEASE_NO_CHANGES("RELEASE_NO_CHANGES", "发布清单没有变化", HttpStatus.CONFLICT),
```

`DocumentRepository` 明确拆成两个入口：

```java
CreatedDocument createDocument(long tenantId, long knowledgeBaseId, long actorUserId,
        String title, SourceFile source, String requestId);
CreatedDocument createVersion(long tenantId, long knowledgeBaseId, long documentId,
        long actorUserId, SourceFile source, String requestId);
Optional<CreatedDocument> findByUploadRequest(long tenantId, String requestId);
```

`createVersion` 先 `SELECT ... FOR UPDATE` 锁文档并校验知识库归属，再查相同 SHA，最后以 `MAX(version_number)+1` 插入；唯一约束异常只允许重读同一请求，不能吞掉真实冲突。新版本创建时不修改 `current_draft_version_id`。

`createDocument` 创建 v1 时也不再立即写 `current_draft_version_id`；v1 与后续版本都必须完成双索引校验后才由 Task 3 推进草稿指针。上传响应字段由含义错误的 `currentDraftVersion` 改为 `createdVersion`：

```java
public record DocumentResponse(
        long id, long tenantId, long knowledgeBaseId, String title,
        DocumentVersionResponse createdVersion, Long currentPublishedVersionId) {}
```

- [ ] **Step 4: 拆分上传服务并写入 MinIO**

```java
@Transactional
public CreatedDocument uploadNewVersion(
        AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId,
        String originalFilename, String declaredMimeType, byte[] content, String requestId) {
    knowledgeBaseService.get(principal, tenantId, knowledgeBaseId);
    ValidatedUpload validated = uploadPolicy.validate(originalFilename, declaredMimeType, content);
    SourceFile source = toSource(validated);
    CreatedDocument created = repository.createVersion(
            tenantId, knowledgeBaseId, documentId, principal.userId(), source, requestId);
    objectStore.put(created.version().sourceObjectKey(), new ByteArrayInputStream(content),
            content.length, validated.mimeType());
    return created;
}
```

同一请求命中已有版本时，服务比较 `documentId + sourceSha256`；完全一致直接返回，不再写 MinIO，不一致抛 `IDEMPOTENCY_KEY_REUSED`。

- [ ] **Step 5: 增加 multipart 新版本接口**

```java
@PostMapping(value = "/{documentId}/versions", consumes = "multipart/form-data")
public ApiResponse<DocumentResponse> uploadVersion(
        @PathVariable long tenantId, @PathVariable long knowledgeBaseId,
        @PathVariable long documentId, @RequestParam("file") MultipartFile file,
        Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    String requestId = prepareRequestId(request, response);
    try {
        return ApiResponse.success(DocumentResponse.from(uploadService.uploadNewVersion(
                principal(authentication), tenantId, knowledgeBaseId, documentId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes(), requestId)));
    } catch (IOException exception) {
        throw new BusinessException(ApiErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, exception);
    }
}
```

- [ ] **Step 6: 运行测试并提交**

Run: `mvn -Dtest=DocumentRepositoryIntegrationTest,DocumentUploadServiceTest,DocumentControllerTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/common/api src/main/java/com/xjjk/knowledge/document src/test/java/com/xjjk/knowledge/document
git commit -m "feat: upload new document versions"
```

---

### Task 3: READY 后原子切换草稿并阻止延迟任务覆盖

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/indexing/ChunkIndexMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/indexing/MybatisChunkIndexRepository.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/indexing/ChunkIndexRepository.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/indexing/MybatisChunkIndexRepositoryTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/indexing/DraftIndexingServiceTest.java`

- [ ] **Step 1: 写失败测试证明 v2 创建时不替换草稿、v2 READY 后替换、迟到 v1 不反向覆盖**

```java
@Test
void promotesOnlyLatestReadyVersionAndRegistersOldDraftCleanup() {
    long v1 = readyVersion(1);
    long v2 = uploadedVersion(2);
    assertThat(currentDraft()).isEqualTo(v1);
    markReady(v2);
    assertThat(currentDraft()).isEqualTo(v2);
    assertThat(cleanup(v1, "DRAFT", "DRAFT_REPLACED")).isPresent();
    assertThat(markReady(v1)).isFalse();
    assertThat(currentDraft()).isEqualTo(v2);
}
```

- [ ] **Step 2: 运行测试确认旧实现失败**

Run: `mvn -Dtest=MybatisChunkIndexRepositoryTest,DraftIndexingServiceTest test`

Expected: FAIL，旧上传立即切草稿且 `markReady` 不负责指针事务。

- [ ] **Step 3: 把 READY、草稿指针和清理登记放进同一短事务**

仓储使用下面的条件更新语义：

```sql
UPDATE kb_document d
   SET d.current_draft_version_id = :versionId,
       d.row_version = d.row_version + 1,
       d.updated_by = :actorUserId
 WHERE d.tenant_id = :tenantId
   AND d.id = :documentId
   AND d.is_deleted = 0
   AND NOT EXISTS (
       SELECT 1 FROM kb_document_version newer
        WHERE newer.tenant_id=d.tenant_id
          AND newer.document_id=d.id
          AND newer.version_number>:versionNumber
          AND newer.status<>'FAILED')
```

`markReady(...)` 返回布尔值，并在同一 `@Transactional` 方法中完成：锁文档、校验任务租约、更新版本 READY、尝试切换草稿、为旧草稿插入 `DRAFT_REPLACED` 清理任务。若存在更新候选版本，允许当前版本保留 READY，但不得成为当前草稿。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -Dtest=MybatisChunkIndexRepositoryTest,DraftIndexingServiceTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/retrieval/indexing src/test/java/com/xjjk/knowledge/retrieval/indexing
git commit -m "feat: promote latest ready draft atomically"
```

---

### Task 4: 登记并安全清理失败草稿、旧草稿和 MinIO 孤儿对象

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupTask.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupWorker.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/cleanup/OrphanSourceObjectScanner.java`
- Create: `src/main/java/com/xjjk/knowledge/document/storage/StoredSourceObject.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/storage/SourceObjectStore.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/storage/MinioSourceObjectStore.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionArtifactRepository.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupWorkerTest.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/cleanup/OrphanSourceObjectScannerTest.java`

- [ ] **Step 1: 写失败测试覆盖引用保护、失败重试和安全时间窗**

```java
@Test
void neverDeletesCurrentDraftOrCurrentReleaseVersion() {
    when(repository.isReferenced(task)).thenReturn(true);
    worker.process(task);
    verifyNoInteractions(keywordIndex, vectorIndex);
    verify(repository).complete(task.id());
}

@Test
void retriesWhenOneDerivedStoreFails() {
    doThrow(new SearchIndexUnavailableException()).when(keywordIndex).deleteVersion(
            IndexLayer.DRAFT, 1, 8, 13);
    worker.process(task);
    verify(repository).retry(task.id(), "DERIVED_INDEX_CLEANUP_FAILED");
}

@Test
void deletesOnlyOldUnreferencedSourceObjects() {
    when(store.listSourceObjectsOlderThan(cutoff)).thenReturn(List.of(oldOrphan, recentObject));
    scanner.scan();
    verify(store).delete(oldOrphan.key());
    verify(store, never()).delete(recentObject.key());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=DerivedCleanupWorkerTest,OrphanSourceObjectScannerTest test`

Expected: FAIL，通用清理和对象枚举接口尚不存在。

- [ ] **Step 3: 实现租约领取与双重引用门禁**

```java
public void process(DerivedCleanupTask task) {
    if (repository.isCurrentDraft(task) || repository.isInCurrentRelease(task)) {
        repository.complete(task.id());
        return;
    }
    try {
        keywordIndex.deleteVersion(task.layer(), task.tenantId(), task.documentId(), task.versionId());
        vectorIndex.deleteVersion(task.layer(), task.tenantId(), task.documentId(), task.versionId());
        repository.completeOwned(task.id(), task.leaseToken());
    } catch (RuntimeException exception) {
        repository.retryOwned(task.id(), task.leaseToken(), "DERIVED_INDEX_CLEANUP_FAILED");
    }
}
```

Worker 通过 `locked_until < NOW()` 条件领取，删除前和完成前都重新检查指针；失败使用有限指数退避，达到上限标记 `FAILED`，保留诊断信息。

- [ ] **Step 4: 实现 MinIO 孤儿扫描**

`SourceObjectStore` 增加受限接口：

```java
List<StoredSourceObject> listSourceObjectsOlderThan(Instant cutoff, int limit);
void delete(String objectKey);
```

扫描器只处理早于 24 小时安全窗口的 `tenant/.../source` 对象，并通过 `source_object_key` 查询确认无版本引用后删除；每轮限制 100 个对象，避免全桶扫描阻塞。

- [ ] **Step 5: 在入库重试耗尽时登记 `INGESTION_FAILED`**

`markFailedIfOwned` 成功且任务进入最终失败状态时插入通用清理任务；普通 `RETRY` 不清理，避免下一次尝试需要重复写完整索引。

- [ ] **Step 6: 运行测试并提交**

Run: `mvn -Dtest=DerivedCleanupWorkerTest,OrphanSourceObjectScannerTest,IngestionWorkerTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/publication/cleanup src/main/java/com/xjjk/knowledge/document src/test/java/com/xjjk/knowledge/publication/cleanup src/test/java/com/xjjk/knowledge/document/task
git commit -m "feat: clean unreferenced knowledge artifacts"
```

---

### Task 5: 创建不可变 Release 清单和幂等登记服务

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/publication/release/KnowledgeRelease.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseItem.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseStatus.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseReplacement.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/MybatisReleaseRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseService.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseRepositoryIntegrationTest.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseServiceTest.java`

- [ ] **Step 1: 写失败测试覆盖完整快照、无变化、幂等键和并发基线**

```java
@Test
void createsFullSnapshotWhileReplacingOnlySelectedDocuments() {
    KnowledgeRelease release = service.create(principal, 1, 7,
            List.of(new ReleaseReplacement(12, 102)), "release-2");
    assertThat(repository.items(release.id()))
            .extracting(ReleaseItem::documentId, ReleaseItem::versionId)
            .containsExactlyInAnyOrder(tuple(11L, 91L), tuple(12L, 102L), tuple(13L, 88L));
    assertThat(release.baseReleaseId()).isEqualTo(activeReleaseId);
    assertThat(release.status()).isEqualTo(ReleaseStatus.PREPARING);
}

@Test
void rejectsNoopAndChangedReuseOfRequestId() {
    assertThatThrownBy(() -> createSameManifest("request-a"))
            .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.RELEASE_NO_CHANGES));
    createReplacement("request-b", 12, 102);
    assertThatThrownBy(() -> createReplacement("request-b", 12, 103))
            .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.errorCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReleaseRepositoryIntegrationTest,ReleaseServiceTest test`

Expected: FAIL，Release 领域尚不存在。

- [ ] **Step 3: 实现规范化清单和 SHA-256**

```java
static String manifestSha256(List<ReleaseItem> items) {
    String canonical = items.stream()
            .sorted(Comparator.comparingLong(ReleaseItem::documentId))
            .map(item -> item.documentId() + ":" + item.versionId() + ":" + item.contentManifestSha256())
            .collect(Collectors.joining("\n"));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
}
```

`ReleaseService.create` 在一个短事务中锁知识库，读取活动 Release 全量 Item，覆盖选择项，校验每个目标版本 READY 且归属正确，拒绝空变化，再写 Release、Item、Task 和 Outbox。幂等重试必须比较 `knowledgeBaseId + manifestSha256`。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -Dtest=ReleaseRepositoryIntegrationTest,ReleaseServiceTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/publication/release src/test/java/com/xjjk/knowledge/publication/release
git commit -m "feat: register immutable knowledge releases"
```

---

### Task 6: 实现 Release Worker、RabbitMQ 唤醒和原子激活

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseTask.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseTaskMapper.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseTaskRepository.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseWorker.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseTaskScanner.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseWakeupListener.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseProperties.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseConflictException.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/OutboxEvent.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/OutboxMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/OutboxPublisher.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/task/IngestionMessagingConfiguration.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/indexing/PublicationIndexService.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseWorkerTest.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseActivationIntegrationTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/task/IngestionPipelineIntegrationTest.java`

- [ ] **Step 1: 写失败测试覆盖成功、失败和两个并发 Release**

```java
@Test
void activatesOnlyAfterEveryChangedVersionIsPrepared() {
    worker.process(taskId);
    InOrder order = inOrder(indexes, repository);
    order.verify(indexes).preparePublished(versionA);
    order.verify(indexes).preparePublished(versionB);
    order.verify(repository).activate(releaseId, taskLease);
}

@Test
void keepsOldReleaseWhenSecondIndexPreparationFails() {
    doThrow(new SearchIndexUnavailableException()).when(indexes).preparePublished(versionB);
    worker.process(taskId);
    assertThat(repository.currentReleaseId(knowledgeBaseId)).isEqualTo(oldReleaseId);
    assertThat(repository.release(releaseId).status()).isEqualTo(ReleaseStatus.PREPARING);
    assertThat(repository.task(taskId).status()).isEqualTo("RETRY");
}

@Test
void marksStaleBaseReleaseAsConflict() {
    activateOtherReleaseAfterPreparation();
    worker.process(taskId);
    assertThat(repository.release(releaseId).status()).isEqualTo(ReleaseStatus.CONFLICT);
    assertThat(repository.currentReleaseId(knowledgeBaseId)).isNotEqualTo(releaseId);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReleaseWorkerTest,ReleaseActivationIntegrationTest,IngestionPipelineIntegrationTest test`

Expected: FAIL，任务租约、事件路由和激活事务尚未实现。

- [ ] **Step 3: 让 Outbox 按事件类型路由**

`OutboxEvent` 改为包含 `eventType`、`taskId`。`OutboxMapper` 分别连接 `kb_ingestion_task` 与 `kb_release_task`；`OutboxPublisher` 根据事件类型选择 routing key：

```java
String routingKey = switch (event.eventType()) {
    case "DOCUMENT_INGESTION_REQUESTED" -> properties.getRoutingKey();
    case "KNOWLEDGE_RELEASE_REQUESTED" -> releaseProperties.getRoutingKey();
    default -> throw new IllegalStateException("未知 Outbox 事件: " + event.eventType());
};
rabbitTemplate.convertAndSend(properties.getExchange(), routingKey, Long.toString(event.taskId()));
```

RabbitMQ 只快速唤醒；扫描器仍定期领取 `PENDING/RETRY` 和租约过期任务。

- [ ] **Step 4: 实现 Worker 和短事务激活**

```java
public void process(long taskId) {
    ReleaseTaskLease lease = tasks.claim(taskId, properties.workerId(), properties.leaseDuration())
            .orElse(null);
    if (lease == null) return;
    KnowledgeRelease release = releases.requirePreparing(lease.releaseId());
    try {
        for (DocumentVersion changed : releases.changedVersions(release.id())) {
            indexes.preparePublished(changed);
        }
        releases.activate(release.id(), lease);
        tasks.complete(lease);
    } catch (ReleaseConflictException conflict) {
        releases.markConflict(release.id(), lease);
        tasks.complete(lease);
    } catch (RuntimeException failure) {
        tasks.retryOrFail(lease, "RELEASE_PREPARE_FAILED");
    }
}
```

`activate` 的 MySQL 事务锁知识库，验证 `current_release_id == base_release_id`、`row_version == base_row_version`，重新核对 Item 的版本修订号和 manifest，然后一次性：旧 Release→SUPERSEDED、新 Release→ACTIVE、切 `current_release_id`、同步所有文档兼容指针和状态、写审计、登记不再引用的正式索引清理任务。

- [ ] **Step 5: 运行测试并提交**

Run: `mvn -Dtest=ReleaseWorkerTest,ReleaseActivationIntegrationTest,IngestionPipelineIntegrationTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/publication/release src/main/java/com/xjjk/knowledge/document/task src/main/java/com/xjjk/knowledge/retrieval/indexing src/test/java/com/xjjk/knowledge/publication/release src/test/java/com/xjjk/knowledge/document/task
git commit -m "feat: prepare and activate knowledge releases"
```

---

### Task 7: 提供 Release API 并让旧发布入口统一委托

**Files:**
- Create: `src/main/java/com/xjjk/knowledge/publication/release/web/ReleaseController.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/web/CreateReleaseRequest.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/web/ReleaseResponse.java`
- Create: `src/main/java/com/xjjk/knowledge/publication/release/web/ReleaseDetailResponse.java`
- Modify: `src/main/java/com/xjjk/knowledge/publication/PublicationService.java`
- Modify: `src/main/java/com/xjjk/knowledge/document/web/DocumentController.java`
- Create: `src/test/java/com/xjjk/knowledge/publication/release/web/ReleaseControllerTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/document/web/DocumentControllerTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/publication/PublicationServiceTest.java`

- [ ] **Step 1: 写失败的控制器测试**

```java
mockMvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/7/releases")
        .header("X-Request-Id", "batch-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"replacements\":[{\"documentId\":11,\"versionId\":91}]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.status").value("PREPARING"));

mockMvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/7/releases/4/rollback")
        .header("X-Request-Id", "rollback-4"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.baseReleaseId").exists());
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReleaseControllerTest,DocumentControllerTest,PublicationServiceTest test`

Expected: FAIL，Release 路由和兼容委托尚不存在。

- [ ] **Step 3: 实现知识库级 API**

接口固定为：

```text
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases
GET  /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}
POST /api/v1/admin/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/releases/{releaseId}/rollback
```

创建和回滚返回 HTTP 202；列表与详情返回 200。请求体使用：

```java
public record CreateReleaseRequest(@NotEmpty List<@Valid ReplacementRequest> replacements) {}
public record ReplacementRequest(@Positive long documentId, @Positive long versionId) {}
```

- [ ] **Step 4: 让文档级 publish/rollback/disable 变为 Release 适配器**

`PublicationService` 不再直接写 ES/Milvus 或文档指针，只构造一个 Release replacement；停用则构造移除目标文档的 Release。旧接口返回 `ReleaseResponse`，保证任何调用路径都无法绕过 Release。

- [ ] **Step 5: 运行测试并提交**

Run: `mvn -Dtest=ReleaseControllerTest,DocumentControllerTest,PublicationServiceTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/publication src/main/java/com/xjjk/knowledge/document/web src/test/java/com/xjjk/knowledge/publication src/test/java/com/xjjk/knowledge/document/web
git commit -m "feat: expose knowledge release api"
```

---

### Task 8: 用当前 Release 清单终审线上检索并修正恢复扫描

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionMapper.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidator.java`
- Modify: `src/main/java/com/xjjk/knowledge/retrieval/indexing/IndexRecoveryMapper.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/service/PublishedVersionValidatorIntegrationTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/service/HybridRetrievalServiceTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/retrieval/indexing/IndexRecoveryRepositoryIntegrationTest.java`

- [ ] **Step 1: 写失败测试证明预写版本、旧版本和冲突 Release 均不可见**

```java
@Test
void acceptsOnlyItemsFromCurrentActiveRelease() {
    seedPublishedIndexCandidates(versionInCurrentRelease, prewrittenVersion, oldReleaseVersion);
    assertThat(validator.validate(tenantId, candidates))
            .extracting(candidate -> candidate.chunk().versionId())
            .containsExactly(versionInCurrentRelease);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=PublishedVersionValidatorIntegrationTest,HybridRetrievalServiceTest,IndexRecoveryRepositoryIntegrationTest test`

Expected: FAIL，旧 Mapper 只检查文档级发布指针。

- [ ] **Step 3: 改成 Release Item 批量终审**

核心 SQL：

```sql
SELECT CONCAT(item.document_id, ':', item.version_id)
  FROM kb_knowledge_base kb
  JOIN kb_release r ON r.id=kb.current_release_id
                   AND r.tenant_id=kb.tenant_id
                   AND r.status='ACTIVE'
  JOIN kb_release_item item ON item.release_id=r.id
 WHERE kb.tenant_id=:tenantId
   AND kb.status='ENABLED'
   AND kb.is_deleted=0
   AND ((item.document_id=:documentId AND item.version_id=:versionId))
```

MyBatis `<foreach>` 继续批量生成候选条件，空列表仍直接返回。恢复扫描改为遍历当前活动 Release Item，而不是 `kb_document.current_published_version_id`。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -Dtest=PublishedVersionValidatorIntegrationTest,HybridRetrievalServiceTest,IndexRecoveryRepositoryIntegrationTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/retrieval src/test/java/com/xjjk/knowledge/retrieval
git commit -m "feat: validate retrieval against active release"
```

---

### Task 9: 完成历史 Release 回滚、停用和正式索引清理闭环

**Files:**
- Modify: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseService.java`
- Modify: `src/main/java/com/xjjk/knowledge/publication/release/ReleaseWorker.java`
- Modify: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupRepository.java`
- Modify: `src/main/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupWorker.java`
- Modify: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseServiceTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/publication/release/ReleaseWorkerTest.java`
- Modify: `src/test/java/com/xjjk/knowledge/publication/cleanup/DerivedCleanupWorkerTest.java`

- [ ] **Step 1: 写失败测试覆盖回滚重建和最后一份文档停用**

```java
@Test
void rollbackCreatesNewReleaseAndRebuildsMissingHistoricalIndexes() {
    KnowledgeRelease rollback = service.rollback(principal, tenantId, knowledgeBaseId,
            historicalReleaseId, "rollback-r1");
    worker.process(taskId(rollback));
    verify(indexes).preparePublished(historicalVersion);
    assertThat(repository.currentReleaseId(knowledgeBaseId)).isEqualTo(rollback.id());
    assertThat(repository.release(historicalReleaseId).status()).isEqualTo(ReleaseStatus.SUPERSEDED);
}

@Test
void disablingLastDocumentActivatesEmptyRelease() {
    KnowledgeRelease release = service.disableDocument(principal, tenantId, knowledgeBaseId,
            documentId, "disable-last");
    worker.process(taskId(release));
    assertThat(repository.items(release.id())).isEmpty();
    assertThat(repository.currentPublishedVersion(documentId)).isNull();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReleaseServiceTest,ReleaseWorkerTest,DerivedCleanupWorkerTest test`

Expected: FAIL，回滚和空清单边界尚未完整实现。

- [ ] **Step 3: 实现不可变历史回滚和引用感知清理**

回滚复制历史 Release 的完整 Item 到新 `PREPARING` Release；Worker 对每个目标版本执行 `verifyVersion`，不存在或指纹不一致时调用 `preparePublished` 重建。清理 Worker 使用下面的门禁：

```sql
SELECT COUNT(*)
  FROM kb_knowledge_base kb
  JOIN kb_release_item item ON item.release_id=kb.current_release_id
 WHERE kb.tenant_id=:tenantId
   AND item.document_id=:documentId
   AND item.version_id=:versionId
```

只有计数为 0 才允许删除正式层数据。历史 Release 仍保留清单；未来回滚时可从 MySQL Chunk 重建。

- [ ] **Step 4: 运行测试并提交**

Run: `mvn -Dtest=ReleaseServiceTest,ReleaseWorkerTest,DerivedCleanupWorkerTest test`

Expected: PASS。

```powershell
git add src/main/java/com/xjjk/knowledge/publication src/test/java/com/xjjk/knowledge/publication
git commit -m "feat: rollback and clean knowledge releases"
```

---

### Task 10: 修复前端请求幂等并增加 Release API 类型

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/api/http.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/api/http.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/api/types.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/api/knowledge.ts`
- Create: `D:/GitCode/order-logistics-agent-admin-web/src/api/knowledge.test.ts`

- [ ] **Step 1: 写失败测试证明 CSRF 重试复用请求号**

```ts
it('reuses the same request id after csrf refresh', async () => {
  fetchMock
    .mockResolvedValueOnce(csrfResponse('old'))
    .mockResolvedValueOnce(errorResponse(403, 'CSRF_INVALID'))
    .mockResolvedValueOnce(csrfResponse('new'))
    .mockResolvedValueOnce(successResponse({ id: 1 }))
  await apiRequest('/mutate', { method: 'POST' })
  const first = new Headers(fetchMock.mock.calls[1][1]?.headers).get('X-Request-Id')
  const second = new Headers(fetchMock.mock.calls[3][1]?.headers).get('X-Request-Id')
  expect(second).toBe(first)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- src/api/http.test.ts src/api/knowledge.test.ts`

Expected: FAIL，当前递归重试每次生成新请求号且无 Release API。

- [ ] **Step 3: 把请求号提升到重试循环外**

```ts
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method || 'GET').toUpperCase()
  const stableRequestId = mutationMethods.has(method) ? requestId() : null
  return executeApiRequest(path, init, true, stableRequestId)
}

async function executeApiRequest<T>(
  path: string,
  init: RequestInit,
  canRetryCsrf: boolean,
  stableRequestId: string | null,
): Promise<T> {
  const headers = new Headers(init.headers)
  if (stableRequestId) headers.set('X-Request-Id', stableRequestId)
  const response = await fetch(path, { ...init, headers, credentials: 'include' })
  try { return (await parseEnvelope<T>(response)).data }
  catch (error) {
    if (canRetryCsrf && error instanceof ApiError && error.code === 'CSRF_INVALID') {
      csrf = null
      return executeApiRequest(path, init, false, stableRequestId)
    }
    throw error
  }
}
```

- [ ] **Step 4: 增加前端类型和 API**

```ts
export type ReleaseStatus = 'PREPARING' | 'ACTIVE' | 'SUPERSEDED' | 'FAILED' | 'CONFLICT'
export interface KnowledgeRelease {
  id: number
  knowledgeBaseId: number
  releaseNumber: number
  status: ReleaseStatus
  baseReleaseId: number | null
  manifestSha256: string
  failureCode: string | null
  createdAt: string
  activatedAt: string | null
  items: ReleaseItem[]
}
export interface ReleaseItem { documentId: number; versionId: number; contentManifestSha256: string }

export interface UploadedDocument {
  id: number
  tenantId: number
  knowledgeBaseId: number
  title: string
  createdVersion: DocumentVersion
  currentPublishedVersionId: number | null
}
```

`knowledgeApi` 增加 `uploadDocumentVersion`、`createRelease`、`listReleases`、`releaseDetail`、`rollbackRelease`。

- [ ] **Step 5: 运行测试并提交前端**

Run: `npm test -- src/api/http.test.ts src/api/knowledge.test.ts`

Expected: PASS。

```powershell
git add src/api
git commit -m "feat: add release api client"
```

---

### Task 11: 改造文档列表和详情页

**Files:**
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/views/DocumentsView.vue`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/views/DocumentsView.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/views/DocumentDetailView.vue`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/views/DocumentDetailView.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/styles.css`

- [ ] **Step 1: 写失败组件测试**

```ts
it('shows online draft and processing versions separately', async () => {
  const wrapper = mountDocuments([documentWithVersions({ online: 1, draft: 2, processing: 3 })])
  expect(wrapper.text()).toContain('线上 v1')
  expect(wrapper.text()).toContain('草稿 v2')
  expect(wrapper.text()).toContain('处理中 v3')
})

it('uploads a new version to the selected document', async () => {
  const wrapper = mountDetail(documentV1)
  await wrapper.get('[data-test="new-version-file"]').trigger('change', { target: { files: [file] } })
  await wrapper.get('[data-test="upload-new-version"]').trigger('click')
  expect(knowledgeApi.uploadDocumentVersion).toHaveBeenCalledWith(1, 5, 9, file)
})

it('publishes selected ready drafts as one release', async () => {
  const wrapper = mountDocuments([readyDocumentA, readyDocumentB])
  await wrapper.get('[data-test="select-document-11"]').setValue(true)
  await wrapper.get('[data-test="select-document-12"]').setValue(true)
  await wrapper.get('[data-test="batch-publish"]').trigger('click')
  expect(knowledgeApi.createRelease).toHaveBeenCalledWith(1, 5, [
    { documentId: 11, versionId: 91 }, { documentId: 12, versionId: 102 },
  ])
})
```

- [ ] **Step 2: 运行组件测试确认失败**

Run: `npm test -- src/views/DocumentsView.test.ts src/views/DocumentDetailView.test.ts`

Expected: FAIL，状态展示、选择和新版本入口尚不存在。

- [ ] **Step 3: 改造文档列表**

每张卡片从 `versions` 计算：

```ts
const onlineVersion = (item: KnowledgeDocument) =>
  item.versions.find((value) => value.id === item.currentPublishedVersionId)
const draftVersion = (item: KnowledgeDocument) =>
  item.versions.find((value) => value.id === item.currentDraftVersionId)
const processingVersion = (item: KnowledgeDocument) =>
  item.versions.find((value) => !['READY', 'PUBLISHED', 'ARCHIVED', 'FAILED'].includes(value.status))
```

只有 `currentDraftVersionId` 对应 READY 且不同于线上版本的文档可勾选。点击卡片与复选框事件必须分离，避免选择时跳转详情。批量发布提交 `documentId + currentDraftVersionId` 并显示 Release 已进入准备状态。

- [ ] **Step 4: 增加详情页“上传新版本”**

详情页提供独立文件选择器，调用 `/documents/{documentId}/versions`。上传成功后选择最新版本并轮询任务；相同内容、请求号复用和存储异常直接显示后端中文错误，不自行猜测错误原因。

- [ ] **Step 5: 运行测试、类型检查并提交前端**

Run:

```powershell
npm test -- src/views/DocumentsView.test.ts src/views/DocumentDetailView.test.ts
npm run typecheck
```

Expected: PASS。

```powershell
git add src/views src/styles.css
git commit -m "feat: manage document versions and batch releases"
```

---

### Task 12: 增加 Release 历史页并执行全链路验证

**Files:**
- Create: `D:/GitCode/order-logistics-agent-admin-web/src/views/ReleaseHistoryView.vue`
- Create: `D:/GitCode/order-logistics-agent-admin-web/src/views/ReleaseHistoryView.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/router.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/router.test.ts`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/views/DocumentsView.vue`
- Modify: `D:/GitCode/order-logistics-agent-admin-web/src/styles.css`

- [ ] **Step 1: 写失败测试覆盖历史、失败原因和回滚确认**

```ts
it('lists releases and rolls a historical release into a new preparing release', async () => {
  const wrapper = mountReleaseHistory([activeRelease, supersededRelease])
  expect(wrapper.text()).toContain('当前线上')
  expect(wrapper.text()).toContain('Release #1')
  await wrapper.get('[data-test="rollback-release-1"]').trigger('click')
  await wrapper.get('[data-test="confirm-rollback"]').trigger('click')
  expect(knowledgeApi.rollbackRelease).toHaveBeenCalledWith(1, 5, supersededRelease.id)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- src/views/ReleaseHistoryView.test.ts src/router.test.ts`

Expected: FAIL，页面和路由不存在。

- [ ] **Step 3: 实现 Release 历史页**

新增路由：

```ts
{
  path: 'knowledge-bases/:knowledgeBaseId/releases',
  name: 'release-history',
  component: ReleaseHistoryView,
}
```

页面显示编号、状态、清单中文档数、操作人、创建/激活时间、失败码；只有非 ACTIVE 历史 Release 提供回滚。回滚后轮询新 Release，直至 `ACTIVE/FAILED/CONFLICT`，不会把 PREPARING 误报为成功。

- [ ] **Step 4: 运行两个仓库的完整自动化验证**

后端：

```powershell
mvn test
```

Expected: 全部测试 PASS。

前端：

```powershell
npm test
npm run lint
npm run build
```

Expected: Vitest、ESLint、TypeScript 与 Vite 构建全部成功。

- [ ] **Step 5: 做本地真实基础设施验收**

依次执行并保留请求号、版本号和 Release 编号证据：

1. 上传一份新 PDF，等待 v1 READY。
2. 对同一文档上传内容不同的 PDF，确认生成 v2 而不是新文档。
3. 重复上传相同 PDF，确认返回 `DOCUMENT_CONTENT_UNCHANGED`。
4. 同时选择两个 READY 草稿批量发布，确认激活前检索仍只见旧 Release，激活后两份一起可见。
5. 查看 ES/Milvus 中仍可能存在旧数据，但检索终审不返回旧版本。
6. 回滚历史 Release，确认创建新 Release 并整体恢复。
7. 停用最后一个文档，确认空 Release 生效且检索不返回该文档。
8. 等待清理 Worker，确认未被当前草稿或当前 Release 引用的派生索引被删除。

- [ ] **Step 6: 提交前端页面并检查两个仓库工作区**

```powershell
git add src/views/ReleaseHistoryView.vue src/views/ReleaseHistoryView.test.ts src/router.ts src/router.test.ts src/views/DocumentsView.vue src/styles.css
git commit -m "feat: add knowledge release history"
git status --short
git -C D:\GitCode\order-logistics-knowledge-service status --short
```

Expected: 前端工作区为空；后端只保留用户已有的 `src/main/resources/application-local.yml` 未提交修改。

---

## 最终验收映射

- 文档 v2/v3、幂等、SHA 去重：Task 2。
- READY 后草稿切换与延迟任务保护：Task 3。
- 旧草稿、失败草稿、正式索引和 MinIO 孤儿清理：Task 4、Task 9。
- 完整 Release 清单、批量原子发布和并发冲突：Task 5、Task 6。
- 单文档入口统一走 Release：Task 7。
- MySQL 活动 Release 终审与索引恢复：Task 8。
- 历史回滚和停用：Task 9。
- 稳定请求号、管理端状态、批量发布、新版本上传和 Release 历史：Task 10 至 Task 12。
- 后端、前端及真实 PDF 全链路验证：Task 12。
