INSERT INTO `ai_model_provider`
    (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
SELECT
    'SYSTEM_Memory_tencentdb',
    'Memory', 'tencentdb',
    'TencentDB Agent Memory',
    '[{"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},{"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},{"key":"llm_base_url","label":"记忆 LLM 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"llm_api_key","label":"记忆 LLM 密钥","type":"password"},{"key":"llm_model","label":"记忆 LLM 模型","type":"string"},{"key":"embedding_base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"embedding_api_key","label":"Embedding 密钥","type":"password"},{"key":"embedding_model","label":"Embedding 模型","type":"string"},{"key":"embedding_dimensions","label":"向量维度","type":"integer","default":1024},{"key":"embedding_send_dimensions","label":"发送 dimensions","type":"boolean","default":true,"help":"关闭后不向不兼容的 Embedding 服务发送 dimensions 字段。"}]',
    4,
    1,
    NOW(),
    1,
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider` WHERE `id` = 'SYSTEM_Memory_tencentdb'
);

INSERT INTO `ai_model_config`
    (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `config_json`, `doc_link`, `remark`, `sort`, `updater`, `update_date`, `creator`, `create_date`)
SELECT
    'Memory_tencentdb',
    'Memory',
    'tencentdb',
    'TencentDB Agent Memory',
    0,
    1,
    '{"type":"tencentdb","memory_core_url":"http://tencentdb-memory-core:8420","memory_core_api_key":"","llm_base_url":"","llm_api_key":"","llm_model":"","embedding_base_url":"","embedding_api_key":"","embedding_model":"","embedding_dimensions":1024,"embedding_send_dimensions":true}',
    'https://github.com/TencentCloud/TencentDB-Agent-Memory',
    '分层长期记忆，支持 L0、L1、L2、L3 与混合召回',
    4,
    NULL,
    NULL,
    NULL,
    NULL
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_config` WHERE `id` = 'Memory_tencentdb'
);
