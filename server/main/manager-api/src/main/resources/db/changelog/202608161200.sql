ALTER TABLE `ai_device`
  ADD COLUMN `capability_config_version` bigint NOT NULL DEFAULT 0 COMMENT '设备能力配置版本' AFTER `debug_log_enabled`;
