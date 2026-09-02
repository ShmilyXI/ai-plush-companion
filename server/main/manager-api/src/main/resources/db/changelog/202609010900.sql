-- liquibase formatted sql
-- changeset codex:202609010900

-- These providers run in the server process and do not need an account
-- credential. Without explicit preset metadata the catalog reports them as
-- unknown and a new App runtime is issued without ASR/TTS/Memory entries.
INSERT INTO `ai_companion_model_preset_meta`
(`global_model_id`,`vendor_code`,`vendor_name`,`protocol`,`default_api_url`,
 `credential_requirement`,`credential_fields_json`,`key_url`,`docs_url`,`setup_guide_json`)
VALUES
('ASR_FunASR','funasr','FunASR','local',NULL,'not_required','[]',NULL,NULL,
 JSON_ARRAY('模型随服务端部署，不需要单独配置凭据')),
('ASR_SherpaASR','sherpa','Sherpa ONNX','local',NULL,'not_required','[]',NULL,NULL,
 JSON_ARRAY('模型随服务端部署，不需要单独配置凭据')),
('TTS_EdgeTTS','edge','Edge TTS','edge_tts',NULL,'not_required','[]',NULL,
 'https://github.com/rany2/edge-tts',JSON_ARRAY('由服务端调用 Edge TTS')),
('Memory_nomem','builtin','无记忆','local',NULL,'not_required','[]',NULL,NULL,
 JSON_ARRAY('不保存或召回长期记忆')),
('Memory_mem_local_short','builtin','本地短期记忆','local',NULL,'not_required','[]',NULL,NULL,
 JSON_ARRAY('记忆保存在服务端命名空间文件中')),
('Memory_mem_report_only','builtin','仅上报聊天记录','local',NULL,'not_required','[]',NULL,NULL,
 JSON_ARRAY('仅上报聊天记录，不保存长期记忆'))
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
