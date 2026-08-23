-- liquibase formatted sql

-- changeset codex:202608240100
CREATE TABLE IF NOT EXISTS ai_public_conversation_api_key (
    id VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_prefix VARCHAR(16) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    scopes_json JSON NOT NULL,
    agent_ids_json JSON NULL,
    expires_at DATETIME NULL,
    revoked TINYINT NOT NULL DEFAULT 0,
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_public_conversation_api_key_hash (key_hash),
    KEY idx_public_conversation_api_key_owner (user_id, revoked, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公开对话 API Key 元数据';
