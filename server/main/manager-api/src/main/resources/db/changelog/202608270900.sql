-- liquibase formatted sql

-- changeset codex:202608270900
UPDATE `ai_plugin_definition`
SET `config_schema_json` = JSON_REMOVE(`config_schema_json`, '$.api_key'),
    `secret_fields_json` = JSON_ARRAY(),
    `updated_at` = NOW()
WHERE `capability_id` = 'plugin-weather';
