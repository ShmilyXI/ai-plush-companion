UPDATE `ai_model_provider`
SET `fields` = '[{"key":"base_url","label":"基础URL","type":"string","help":"模型接口地址。DeepSeek 官方接口通常填 https://api.deepseek.com/v1。"},{"key":"model_name","label":"模型名称","type":"string","help":"服务商给出的模型代号。当前默认使用 deepseek-v4-flash。"},{"key":"api_key","label":"API密钥","type":"password"},{"key":"stream_enabled","label":"流式输出","type":"boolean","default":true,"help":"建议打开。模型边生成边交给语音合成，能更早开口；关闭后要等整段回答生成完才说，等待会更久。"},{"key":"thinking_enabled","label":"思考模式","type":"boolean","default":false,"help":"语音聊天建议关闭，响应更快。打开后复杂推理可能更稳，但首句会更慢。"},{"key":"tools_enabled","label":"设备工具","type":"boolean","default":true,"help":"打开后才能调音量、亮度、相机等设备能力。普通聊天会自动少带工具，减少等待。"},{"key":"first_content_timeout","label":"首段等待上限(秒)","type":"number","default":8,"help":"超过这个时间还没有可播放文字就报错，避免板子一直显示说话中。建议 8 秒。"},{"key":"temperature","label":"温度","type":"number","default":1.3,"help":"控制回答的随机程度。低一些更稳、更重复，高一些更活泼、更发散。DeepSeek 对普通对话建议 1.3。"},{"key":"max_tokens","label":"最大输出长度","type":"number","default":2048,"help":"限制一轮最多生成多少文字。调高能答得更长，但长回答会占更多时间；语音聊天建议 2048。"},{"key":"top_p","label":"候选词范围","type":"number","default":1,"help":"控制选词范围。低一些更保守，高一些更多样。通常保持 1，只调温度，不建议两个一起改。"},{"key":"top_k","label":"候选词数量","type":"number","help":"部分兼容接口支持。数值低时用词更固定，高时更多样。DeepSeek 官方接口无需填写。"},{"key":"frequency_penalty","label":"重复惩罚","type":"number","default":0,"help":"控制是否减少重复表达。0 表示不额外限制。DeepSeek 官方接口目前不生效，保持 0 即可。"}]'
WHERE `id` = 'SYSTEM_LLM_openai';

UPDATE `ai_model_config`
SET `is_default` = CASE WHEN `id` = 'LLM_DeepSeekLLM' THEN 1 ELSE 0 END
WHERE `model_type` = 'LLM';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
    COALESCE(`config_json`, JSON_OBJECT()),
    '$.stream_enabled', true,
    '$.thinking_enabled', false,
    '$.tools_enabled', true,
    '$.first_content_timeout', 8,
    '$.temperature', 1.3,
    '$.max_tokens', 2048,
    '$.top_p', 1,
    '$.frequency_penalty', 0
)
WHERE `id` = 'LLM_DeepSeekLLM';
