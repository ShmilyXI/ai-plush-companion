-- liquibase formatted sql

-- changeset codex:202608260900
INSERT INTO `ai_companion_model_preset_meta`
(`global_model_id`,`vendor_code`,`vendor_name`,`protocol`,`default_api_url`,`credential_requirement`,`credential_fields_json`,`key_url`,`docs_url`,`setup_guide_json`)
VALUES
('VAD_SileroVAD','silero','Silero','本地推理',NULL,'not_required','[]',NULL,NULL,JSON_ARRAY('模型随服务端部署，不需要单独配置凭据')),
('Memory_tencentdb','tencentdb','TencentDB Agent Memory','服务端记忆',NULL,'not_required','[]',
 NULL,'https://github.com/TencentCloud/TencentDB-Agent-Memory',
 JSON_ARRAY('记忆服务由后台统一配置','确认服务端 MemoryCore 已启动','保存后验证记忆服务'))
ON DUPLICATE KEY UPDATE
    `vendor_code` = VALUES(`vendor_code`),
    `vendor_name` = VALUES(`vendor_name`),
    `protocol` = VALUES(`protocol`),
    `default_api_url` = VALUES(`default_api_url`),
    `credential_requirement` = VALUES(`credential_requirement`),
    `credential_fields_json` = VALUES(`credential_fields_json`),
    `key_url` = VALUES(`key_url`),
    `docs_url` = VALUES(`docs_url`),
    `setup_guide_json` = VALUES(`setup_guide_json`),
    `updated_at` = NOW();
