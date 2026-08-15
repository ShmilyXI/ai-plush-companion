UPDATE `ai_model_provider`
SET `fields` = '[{"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},{"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},{"key":"llm_base_url","label":"记忆 LLM 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"llm_api_key","label":"记忆 LLM 密钥","type":"password"},{"key":"llm_model","label":"记忆 LLM 模型","type":"string"},{"key":"embedding_base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"embedding_api_key","label":"Embedding 密钥","type":"password"},{"key":"embedding_model","label":"Embedding 模型","type":"string"},{"key":"embedding_dimensions","label":"向量维度","type":"integer","default":1024},{"key":"embedding_send_dimensions","label":"发送 dimensions","type":"boolean","default":true,"help":"关闭后不向不兼容的 Embedding 服务发送 dimensions 字段。"}]',
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_Memory_tencentdb';

UPDATE `ai_model_config`
SET `config_json` = JSON_REMOVE(`config_json`, '$.llm_model_id', '$.embedding_model_id'),
    `update_date` = NOW()
WHERE `id` = 'Memory_tencentdb';

DELETE FROM `ai_model_config` WHERE `id` = 'Embedding_openai';
DELETE FROM `ai_model_provider` WHERE `id` = 'SYSTEM_Embedding_openai';
