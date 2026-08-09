DROP TABLE IF EXISTS `ai_companion_audit`;
DROP TABLE IF EXISTS `ai_companion_subscription`;
DROP TABLE IF EXISTS `ai_companion_plan`;

ALTER TABLE `ai_device`
    DROP COLUMN `has_camera`,
    DROP COLUMN `has_display`;

ALTER TABLE `ai_agent`
    DROP COLUMN `camera_preference_enabled`,
    DROP COLUMN `screen_expression_enabled`,
    DROP COLUMN `companion_cue_config`,
    DROP COLUMN `companion_template_id`,
    DROP COLUMN `personality`,
    DROP COLUMN `user_address`,
    DROP COLUMN `relation_mode`,
    DROP COLUMN `companion_enabled`;
