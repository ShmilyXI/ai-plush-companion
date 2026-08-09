ALTER TABLE `ai_companion_private_model`
  DROP KEY `idx_companion_private_model_template`,
  DROP COLUMN `secret_config_ciphertext`,
  DROP COLUMN `provider_template_id`;
