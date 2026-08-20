-- liquibase formatted sql

-- changeset codex:202608201200
SET @active_version_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'active_version_no'
);
SET @active_version_sql = IF(
    @active_version_exists = 0,
    'ALTER TABLE ai_agent ADD COLUMN active_version_no INT UNSIGNED NULL COMMENT ''当前激活的智能体配置版本号'' AFTER updated_at',
    'SELECT 1'
);
PREPARE active_version_stmt FROM @active_version_sql;
EXECUTE active_version_stmt;
DEALLOCATE PREPARE active_version_stmt;

CREATE TABLE IF NOT EXISTS ai_agent_version_activation_audit (
    id VARCHAR(32) NOT NULL,
    agent_id VARCHAR(32) NOT NULL,
    user_id BIGINT NULL,
    previous_version_no INT UNSIGNED NULL,
    activated_version_no INT UNSIGNED NOT NULL,
    action VARCHAR(24) NOT NULL,
    operator_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_activation_audit_agent_created (agent_id, created_at),
    KEY idx_agent_activation_audit_operator_created (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体配置版本激活审计';

UPDATE ai_agent a
JOIN (
    SELECT agent_id, MAX(version_no) AS version_no
    FROM ai_agent_snapshot
    GROUP BY agent_id
) s ON s.agent_id = a.id
SET a.active_version_no = s.version_no
WHERE a.active_version_no IS NULL;

CREATE TABLE IF NOT EXISTS ai_agent_version_skill_binding (
    id BIGINT NOT NULL,
    agent_id VARCHAR(32) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    version_mode VARCHAR(16) NOT NULL DEFAULT 'LATEST',
    fixed_version INT UNSIGNED NULL,
    override_json JSON NULL,
    trigger_priority INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    migration_source VARCHAR(128) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_version_skill (agent_id, version_no, skill_id),
    KEY idx_agent_version_skill_version (agent_id, version_no, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体配置版本 Skill 绑定';

CREATE TABLE IF NOT EXISTS ai_companion_memory_migration (
    id VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    agent_id VARCHAR(32) NOT NULL,
    source_device_id VARCHAR(64) NOT NULL,
    target_device_id VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    source_count INT NOT NULL DEFAULT 0,
    target_count INT NOT NULL DEFAULT 0,
    imported_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    outcome VARCHAR(24) NOT NULL,
    retryable TINYINT NOT NULL DEFAULT 0,
    recovered TINYINT NOT NULL DEFAULT 1,
    operator_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_memory_migration_owner_created (owner_id, created_at),
    KEY idx_memory_migration_retryable (owner_id, retryable, outcome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪伴记忆迁移审计';
