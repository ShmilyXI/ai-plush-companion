-- liquibase formatted sql

-- changeset codex:202608260900-rollback
DELETE FROM `ai_companion_model_preset_meta`
WHERE `global_model_id` IN ('VAD_SileroVAD', 'Memory_tencentdb');
