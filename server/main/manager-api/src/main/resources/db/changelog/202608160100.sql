INSERT INTO `ai_model_provider`
    (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
SELECT
    'SYSTEM_Embedding_openai',
    'Embedding', 'openai',
    'OpenAI 兼容 Embedding',
    '[{"key":"base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"api_key","label":"Embedding 密钥","type":"password"},{"key":"model_name","label":"Embedding 模型","type":"string"},{"key":"dimensions","label":"向量维度","type":"integer","default":1024},{"key":"send_dimensions","label":"发送 dimensions","type":"boolean","default":true,"help":"关闭后不向不兼容的 Embedding 服务发送 dimensions 字段。"}]',
    1,
    1,
    NOW(),
    1,
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider` WHERE `id` = 'SYSTEM_Embedding_openai'
);

INSERT INTO `ai_model_config`
    (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `config_json`, `doc_link`, `remark`, `sort`, `updater`, `update_date`, `creator`, `create_date`)
SELECT
    'Embedding_openai',
    'Embedding',
    'openai',
    'OpenAI 兼容 Embedding',
    0,
    0,
    '{"type":"openai","base_url":"","api_key":"","model_name":"","dimensions":1024,"send_dimensions":true}',
    NULL,
    '供长期记忆和向量检索使用',
    1,
    NULL,
    NULL,
    NULL,
    NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_config` WHERE `id` = 'Embedding_openai'
);

UPDATE `ai_model_provider`
SET `fields` = '[{"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},{"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},{"key":"llm_model_id","label":"记忆 LLM","type":"string","options":[],"help":"选择已启用的 OpenAI 兼容 LLM。"},{"key":"embedding_model_id","label":"Embedding 模型","type":"string","options":[],"help":"选择已启用的 Embedding 模型。"}]',
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_Memory_tencentdb';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
        `config_json`,
        '$.llm_model_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.llm_model_id')), ''),
        '$.embedding_model_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.embedding_model_id')), '')
    ),
    `update_date` = NOW()
WHERE `id` = 'Memory_tencentdb';
