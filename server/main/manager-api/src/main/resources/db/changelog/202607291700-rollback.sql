-- liquibase formatted sql

-- changeset Codex:202607291700-rollback
ALTER TABLE `ai_device`
    DROP INDEX `uk_ai_device_normalized_mac`,
    DROP COLUMN `normalized_mac_address`;
