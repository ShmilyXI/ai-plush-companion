-- LLM 温度默认值从 1.3 改成 0.8：1.3 下陪伴聊天偶尔跑题、句子说不通。
-- 只改新建模型时表单带出的默认值，和最初由 202608132100 设成 1.3 的内置 DeepSeek 配置；
-- 已经建好的模型配置是管理员自己的设置，不在这里改。
UPDATE `ai_model_provider`
SET `fields` = JSON_SET(
    `fields`,
    CONCAT(JSON_UNQUOTE(REPLACE(JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key'), '.key', '')), '.default'), 0.8,
    CONCAT(JSON_UNQUOTE(REPLACE(JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key'), '.key', '')), '.help'),
    '控制回答的随机程度。低一些更稳、更重复，高一些更活泼、更发散，太高容易跑题、句子说不通。陪伴聊天建议 0.8。'
)
WHERE `id` = 'SYSTEM_LLM_openai'
  AND JSON_SEARCH(`fields`, 'one', 'temperature', NULL, '$[*].key') IS NOT NULL;

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.temperature', 0.8)
WHERE `id` = 'LLM_DeepSeekLLM'
  AND JSON_EXTRACT(`config_json`, '$.temperature') = 1.3;
