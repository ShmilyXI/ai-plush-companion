DROP TABLE IF EXISTS `ai_companion_global_model_credential`;
DROP TABLE IF EXISTS `ai_companion_model_preset_meta`;

ALTER TABLE `ai_companion_private_model`
  DROP COLUMN `credential_required`,
  DROP COLUMN `protocol`,
  DROP COLUMN `vendor_name`;
