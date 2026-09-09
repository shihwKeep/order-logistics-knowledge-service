-- 发布指针切换后再异步清理旧版本索引；失败不会回滚已经生效的新发布版本。
CREATE TABLE kb_published_index_cleanup (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  publish_record_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  version_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cleanup_publish_version (publish_record_id, version_id),
  KEY idx_cleanup_due (status, next_run_at, id),
  KEY idx_cleanup_tenant_document (tenant_id, document_id, version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
