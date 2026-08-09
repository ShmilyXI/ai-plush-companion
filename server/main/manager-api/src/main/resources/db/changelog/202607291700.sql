-- liquibase formatted sql

-- changeset Codex:202607291700
ALTER TABLE `ai_device`
    ADD COLUMN `normalized_mac_address` VARCHAR(64)
        GENERATED ALWAYS AS (LOWER(REPLACE(REPLACE(TRIM(`mac_address`), ':', ''), '-', ''))) STORED
        COMMENT '统一大小写、首尾空格和常见分隔符后的 MAC 地址',
    ADD UNIQUE KEY `uk_ai_device_normalized_mac` (`normalized_mac_address`);
