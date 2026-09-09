-- 文档逻辑主表：文档与版本分离，草稿和已发布版本分别维护指针。
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
  KEY idx_document_tenant_kb (tenant_id, knowledge_base_id, is_deleted),
  KEY idx_document_tenant_draft (tenant_id, current_draft_version_id),
  KEY idx_document_tenant_published (tenant_id, current_published_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 文档不可变版本：每次上传或重新解析形成新版本，线上发布指针不随草稿变化。
CREATE TABLE kb_document_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_number INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'UPLOADED',
  original_filename VARCHAR(255) NOT NULL,
  file_extension VARCHAR(16) NOT NULL,
  mime_type VARCHAR(120) NOT NULL,
  file_size BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  source_object_key VARCHAR(512) NOT NULL,
  parsed_object_key VARCHAR(512) NULL,
  parser_version VARCHAR(80) NULL,
  chunk_strategy_version VARCHAR(80) NULL,
  ocr_required TINYINT(1) NOT NULL DEFAULT 0,
  correction_revision INT NOT NULL DEFAULT 0,
  unit_count INT NOT NULL DEFAULT 0,
  chunk_count INT NOT NULL DEFAULT 0,
  failure_stage VARCHAR(32) NULL,
  last_error_code VARCHAR(64) NULL,
  last_error_message VARCHAR(500) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_version_number (tenant_id, document_id, version_number),
  KEY idx_version_tenant_status (tenant_id, status, updated_at),
  KEY idx_version_tenant_document (tenant_id, document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 可定位原文单元：页、幻灯片、工作表或文本区块均用统一结构保存。
CREATE TABLE kb_document_unit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  unit_type VARCHAR(24) NOT NULL,
  unit_index INT NOT NULL,
  location_label VARCHAR(255) NULL,
  title_path VARCHAR(1000) NULL,
  raw_text MEDIUMTEXT NOT NULL,
  effective_text MEDIUMTEXT NOT NULL,
  ocr_confidence DECIMAL(6,5) NULL,
  low_confidence TINYINT(1) NOT NULL DEFAULT 0,
  content_sha256 CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_unit_version_index (tenant_id, version_id, unit_index),
  KEY idx_unit_tenant_document (tenant_id, document_id, version_id),
  KEY idx_unit_tenant_confidence (tenant_id, version_id, low_confidence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 人工校正历史：正文单元保留当前有效文本，本表保留每次不可变修订。
CREATE TABLE kb_document_unit_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  revision_number INT NOT NULL,
  previous_text MEDIUMTEXT NOT NULL,
  corrected_text MEDIUMTEXT NOT NULL,
  corrected_by BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_unit_revision (tenant_id, unit_id, revision_number),
  KEY idx_revision_tenant_version (tenant_id, version_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 结构化 Chunk：第三阶段的 ES/Milvus 索引均以这里的数据为输入。
CREATE TABLE kb_chunk (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  chunk_index INT NOT NULL,
  title_path VARCHAR(1000) NULL,
  content MEDIUMTEXT NOT NULL,
  token_count INT NOT NULL,
  content_sha256 CHAR(64) NOT NULL,
  location_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk_version_index (tenant_id, version_id, chunk_index),
  KEY idx_chunk_tenant_document (tenant_id, document_id, version_id),
  KEY idx_chunk_tenant_unit (tenant_id, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 数据库任务是处理状态真相；RabbitMQ 仅负责唤醒 Worker。
CREATE TABLE kb_ingestion_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  task_key VARCHAR(160) NOT NULL,
  stage VARCHAR(32) NOT NULL,
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
  UNIQUE KEY uk_ingestion_task_key (task_key),
  KEY idx_task_claim (status, next_run_at, locked_until),
  KEY idx_task_tenant_version (tenant_id, version_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 事务 Outbox：与文档版本和任务同事务登记，异步投递 RabbitMQ。
CREATE TABLE kb_outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  aggregate_type VARCHAR(40) NOT NULL,
  aggregate_id VARCHAR(80) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_publish (status, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
