-- liquibase formatted sql

-- changeset codex:202608291000
CREATE TABLE IF NOT EXISTS ai_app_contact (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    normalized_value VARCHAR(320) NOT NULL,
    verified_at DATETIME NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_contact_value (channel, normalized_value),
    UNIQUE KEY uk_app_contact_user_channel (user_id, channel),
    KEY idx_app_contact_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_app_auth_challenge (
    id VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    normalized_value VARCHAR(320) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_app_challenge_lookup (channel, normalized_value, purpose, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_app_refresh_token (
    id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    last_used_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_refresh_hash (token_hash),
    KEY idx_app_refresh_user (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
