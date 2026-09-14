CREATE TABLE knowledge_cloud_model_budget (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    billing_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hard_limit_micros BIGINT UNSIGNED NOT NULL,
    settled_micros BIGINT UNSIGNED NOT NULL DEFAULT 0,
    reserved_micros BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cloud_budget_month (billing_month),
    CONSTRAINT chk_cloud_budget_total CHECK
        (settled_micros + reserved_micros <= hard_limit_micros)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_cloud_model_call (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    billing_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    logical_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    call_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_micros BIGINT UNSIGNED NOT NULL,
    settled_micros BIGINT UNSIGNED NULL,
    total_tokens BIGINT UNSIGNED NULL,
    provider_request_id VARCHAR(128) NULL,
    error_code VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cloud_call_id (call_id),
    UNIQUE KEY uk_cloud_logical_attempt
        (logical_request_id, call_type, attempt_no),
    KEY idx_cloud_call_month_status (billing_month, status, id),
    CONSTRAINT chk_cloud_call_attempt CHECK (attempt_no > 0),
    CONSTRAINT chk_cloud_call_status CHECK
        (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
