ALTER TABLE `ai_companion_private_model`
  ADD COLUMN `provider_template_id` varchar(32) DEFAULT NULL AFTER `provider_code`,
  ADD COLUMN `secret_config_ciphertext` text DEFAULT NULL AFTER `api_key_ciphertext`,
  ADD KEY `idx_companion_private_model_template` (`provider_template_id`);
