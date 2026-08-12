-- liquibase formatted sql

-- changeset Codex:202608121900
ALTER TABLE `ai_device`
    ADD COLUMN `debug_log_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否记录设备调试日志(0关闭/1开启)' AFTER `has_camera`;
