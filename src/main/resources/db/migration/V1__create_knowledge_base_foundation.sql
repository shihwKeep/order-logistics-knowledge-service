-- 知识库主表：每个租户可维护多个独立知识库。
CREATE TABLE kb_knowledge_base (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
  created_by BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  row_version INT NOT NULL DEFAULT 0,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kb_tenant_name (tenant_id, name),
  KEY idx_kb_tenant_status (tenant_id, status, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 管理操作审计表：记录谁在什么租户下对哪个资源执行了什么操作。
CREATE TABLE kb_audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  actor_tenant_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  resource_type VARCHAR(40) NOT NULL,
  resource_id VARCHAR(80) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  outcome VARCHAR(20) NOT NULL,
  detail_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_tenant_created (tenant_id, created_at),
  KEY idx_audit_actor_created (actor_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
