-- liquibase formatted sql

-- changeset codex:202608201200-rollback
DROP TABLE IF EXISTS ai_agent_version_activation_audit;
DROP TABLE IF EXISTS ai_agent_version_skill_binding;
DROP TABLE IF EXISTS ai_companion_memory_migration;
SET @active_version_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'active_version_no'
);
SET @active_version_sql = IF(
    @active_version_exists = 1,
    'ALTER TABLE ai_agent DROP COLUMN active_version_no',
    'SELECT 1'
);
PREPARE active_version_stmt FROM @active_version_sql;
EXECUTE active_version_stmt;
DEALLOCATE PREPARE active_version_stmt;
