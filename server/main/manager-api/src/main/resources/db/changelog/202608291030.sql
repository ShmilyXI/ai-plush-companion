-- liquibase formatted sql

-- changeset codex:202608291030
CREATE TABLE IF NOT EXISTS ai_companion_conversation (
    id VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    profile_id VARCHAR(64) NOT NULL,
    profile_version_no INT NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL DEFAULT 'app',
    title VARCHAR(255) NOT NULL DEFAULT '',
    last_activity_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_companion_conversation_owner (owner_id, deleted_at, last_activity_at, id),
    KEY idx_companion_conversation_profile (owner_id, profile_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_companion_conversation_turn (
    id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'app',
    user_text TEXT NOT NULL,
    assistant_text TEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_companion_conversation_turn (conversation_id, turn_id),
    KEY idx_companion_turn_conversation (conversation_id, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
