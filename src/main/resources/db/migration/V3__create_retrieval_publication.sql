-- 文档版本索引元数据：索引是派生数据，但必须能证明由哪个模型和内容清单生成。
ALTER TABLE kb_document_version
  ADD COLUMN embedding_model VARCHAR(120) NULL AFTER chunk_strategy_version,
  ADD COLUMN embedding_dimension INT NULL AFTER embedding_model,
  ADD COLUMN embedding_instruction_version VARCHAR(80) NULL AFTER embedding_dimension,
  ADD COLUMN index_manifest_sha256 CHAR(64) NULL AFTER embedding_instruction_version,
  ADD COLUMN indexed_at DATETIME(3) NULL AFTER index_manifest_sha256;

-- 发布记录不可变保存。request_id 同时承担写操作幂等键。
CREATE TABLE kb_publish_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  knowledge_base_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  from_version_id BIGINT UNSIGNED NULL,
  to_version_id BIGINT UNSIGNED NULL,
  action VARCHAR(20) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  chunk_count INT NOT NULL,
  manifest_sha256 CHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_publish_request (tenant_id, request_id),
  KEY idx_publish_tenant_document (tenant_id, document_id, created_at),
  KEY idx_publish_tenant_kb (tenant_id, knowledge_base_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 查询日志只记录运行状态和数量，不持久化用户问题、知识正文或向量。
CREATE TABLE kb_search_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  strategy_version VARCHAR(120) NOT NULL,
  degradation_mode VARCHAR(40) NOT NULL,
  result_code VARCHAR(64) NOT NULL,
  answerable TINYINT(1) NOT NULL,
  vector_candidate_count INT NOT NULL DEFAULT 0,
  keyword_candidate_count INT NOT NULL DEFAULT 0,
  fused_candidate_count INT NOT NULL DEFAULT 0,
  evidence_count INT NOT NULL DEFAULT 0,
  total_duration_ms BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_search_tenant_created (tenant_id, created_at),
  KEY idx_search_result_created (result_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
