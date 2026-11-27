UPDATE `ai_model_provider`
SET `fields` = JSON_SET(
    `fields`,
    CONCAT(JSON_UNQUOTE(REPLACE(JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key'), '.key', '')), '.default'), 1.3,
    CONCAT(JSON_UNQUOTE(REPLACE(JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key'), '.key', '')), '.help'),
    '控制回答的随机程度。低一些更稳、更重复，高一些更活泼、更发散。DeepSeek 对普通对话建议 1.3。'
)
WHERE `id` = 'SYSTEM_LLM_openai'
  AND JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key') IS NOT NULL;

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.temperature', 1.3)
WHERE `id` = 'LLM_DeepSeekLLM'
  AND JSON_EXTRACT(`config_json`, '$.temperature') = 0.8;
