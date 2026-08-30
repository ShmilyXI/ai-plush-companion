-- liquibase formatted sql

-- changeset codex:202608291015
SET @memory_enabled_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'memory_enabled'
);
SET @memory_enabled_sql = IF(
    @memory_enabled_exists = 0,
    'ALTER TABLE ai_agent ADD COLUMN memory_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''App 角色是否启用长期记忆''',
    'SELECT 1'
);
PREPARE memory_enabled_stmt FROM @memory_enabled_sql;
EXECUTE memory_enabled_stmt;
DEALLOCATE PREPARE memory_enabled_stmt;

SET @consumer_deleted_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'consumer_deleted_at'
);
SET @consumer_deleted_sql = IF(
    @consumer_deleted_exists = 0,
    'ALTER TABLE ai_agent ADD COLUMN consumer_deleted_at DATETIME NULL COMMENT ''App 角色软删除时间''',
    'SELECT 1'
);
PREPARE consumer_deleted_stmt FROM @consumer_deleted_sql;
EXECUTE consumer_deleted_stmt;
DEALLOCATE PREPARE consumer_deleted_stmt;

SET @avatar_url_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'avatar_url'
);
SET @avatar_url_sql = IF(
    @avatar_url_exists = 0,
    'ALTER TABLE ai_agent ADD COLUMN avatar_url VARCHAR(512) NULL COMMENT ''App 角色头像地址''',
    'SELECT 1'
);
PREPARE avatar_url_stmt FROM @avatar_url_sql;
EXECUTE avatar_url_stmt;
DEALLOCATE PREPARE avatar_url_stmt;
