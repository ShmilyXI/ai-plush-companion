-- liquibase formatted sql

-- changeset codex:202608270900-rollback
UPDATE `ai_plugin_definition`
SET `config_schema_json` = JSON_SET(
        COALESCE(`config_schema_json`, JSON_OBJECT()),
        '$.api_key', JSON_OBJECT('type', 'string')
    ),
    `secret_fields_json` = JSON_ARRAY('api_key'),
    `updated_at` = NOW()
WHERE `capability_id` = 'plugin-weather';
