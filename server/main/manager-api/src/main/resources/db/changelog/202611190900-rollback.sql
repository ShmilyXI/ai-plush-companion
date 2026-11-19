ALTER TABLE `ai_model_config`
  DROP KEY `idx_ai_model_config_channel`,
  DROP COLUMN `channel_id`;

DROP TABLE IF EXISTS `ai_model_channel`;
