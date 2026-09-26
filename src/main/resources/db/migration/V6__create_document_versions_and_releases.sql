-- 文档上传请求幂等键。先回填存量版本，再收紧非空与唯一约束。
ALTER TABLE kb_document_version
  ADD COLUMN upload_request_id VARCHAR(64) NULL AFTER source_sha256;

UPDATE kb_document_version
   SET upload_request_id=CONCAT('legacy-version-', id)
 WHERE upload_request_id IS NULL;

ALTER TABLE kb_document_version
  MODIFY upload_request_id VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_version_upload_request (tenant_id, upload_request_id);

-- Release 是知识库当前线上文档集合的不可变快照。
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

-- 发布任务以 MySQL 为状态事实；RabbitMQ 只负责快速唤醒 Worker。
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

-- 草稿层和正式层共用一套可租约领取、可重试的派生索引清理队列。
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
  UNIQUE KEY uk_cleanup_target
    (tenant_id, document_id, version_id, index_layer, cleanup_reason),
  KEY idx_cleanup_claim (status, next_run_at, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE kb_knowledge_base
  ADD COLUMN current_release_id BIGINT UNSIGNED NULL AFTER status,
  ADD KEY idx_kb_current_release (tenant_id, current_release_id);

-- 存量文档级发布指针组成每个知识库的首个活动 Release，迁移前后线上可见集合保持一致。
SET SESSION group_concat_max_len = 16777216;

INSERT INTO kb_release
  (tenant_id, knowledge_base_id, release_number, status, base_release_id, request_id,
   manifest_sha256, base_row_version, created_by, created_at, activated_at, updated_at)
SELECT kb.tenant_id,
       kb.id,
       1,
       'ACTIVE',
       NULL,
       CONCAT('legacy-release-', kb.id),
       SHA2(GROUP_CONCAT(
         CONCAT(d.id, ':', d.current_published_version_id, ':',
           COALESCE(v.index_manifest_sha256, SHA2(CONCAT('legacy-version-', v.id), 256)))
         ORDER BY d.id SEPARATOR '\n'), 256),
       kb.row_version,
       kb.updated_by,
       CURRENT_TIMESTAMP(3),
       CURRENT_TIMESTAMP(3),
       CURRENT_TIMESTAMP(3)
  FROM kb_knowledge_base kb
  JOIN kb_document d
    ON d.tenant_id=kb.tenant_id
   AND d.knowledge_base_id=kb.id
   AND d.is_deleted=0
   AND d.current_published_version_id IS NOT NULL
  JOIN kb_document_version v
    ON v.tenant_id=d.tenant_id
   AND v.id=d.current_published_version_id
 GROUP BY kb.tenant_id, kb.id, kb.row_version, kb.updated_by;

INSERT INTO kb_release_item
  (release_id, tenant_id, knowledge_base_id, document_id, version_id, content_manifest_sha256)
SELECT r.id,
       d.tenant_id,
       d.knowledge_base_id,
       d.id,
       d.current_published_version_id,
       COALESCE(v.index_manifest_sha256, SHA2(CONCAT('legacy-version-', v.id), 256))
  FROM kb_release r
  JOIN kb_document d
    ON d.tenant_id=r.tenant_id
   AND d.knowledge_base_id=r.knowledge_base_id
   AND d.is_deleted=0
   AND d.current_published_version_id IS NOT NULL
  JOIN kb_document_version v
    ON v.tenant_id=d.tenant_id
   AND v.id=d.current_published_version_id
 WHERE r.request_id=CONCAT('legacy-release-', r.knowledge_base_id);

UPDATE kb_knowledge_base kb
JOIN kb_release r
  ON r.tenant_id=kb.tenant_id
 AND r.knowledge_base_id=kb.id
 AND r.request_id=CONCAT('legacy-release-', kb.id)
   SET kb.current_release_id=r.id;

-- 旧清理任务迁入通用队列；旧表暂时保留，便于回退旧版本应用。
INSERT IGNORE INTO kb_derived_index_cleanup
  (tenant_id, knowledge_base_id, document_id, version_id, index_layer,
   cleanup_reason, status, retry_count, next_run_at, last_error_code, created_at, updated_at)
SELECT old.tenant_id,
       d.knowledge_base_id,
       old.document_id,
       old.version_id,
       'PUBLISHED',
       'RELEASE_SUPERSEDED',
       old.status,
       old.retry_count,
       old.next_run_at,
       old.last_error_code,
       old.created_at,
       old.updated_at
  FROM kb_published_index_cleanup old
  JOIN kb_document d
    ON d.tenant_id=old.tenant_id
   AND d.id=old.document_id;
