UPDATE `ai_model_provider`
SET `name` = '火山双流式语音合成',
    `fields` = '[{"key":"ws_url","type":"string","label":"WebSocket地址"},{"key":"appid","type":"string","label":"应用ID"},{"key":"access_token","type":"string","label":"访问令牌"},{"key":"resource_id","type":"string","label":"资源ID"},{"key":"speaker","type":"string","label":"默认音色"},{"key":"enable_ws_reuse","type":"boolean","label":"是否开启链接复用","default":true},{"key":"audio_params","type":"dict","label":"音频输出配置"},{"key":"additions","type":"dict","label":"高级文本处理配置"},{"key":"mix_speaker","type":"dict","label":"混音控制配置"}]'
WHERE `id` = 'SYSTEM_TTS_HSDSTTS';

UPDATE `ai_model_config`
SET `model_name` = '火山双流式语音合成',
    `config_json` = JSON_REMOVE(
        JSON_SET(
            COALESCE(`config_json`, JSON_OBJECT()),
            '$.resource_id', 'volc.service_type.10029'
        ),
        '$.access_key_id',
        '$.secret_access_key'
    ),
    `doc_link` = 'https://www.volcengine.com/docs/6561/1329505'
WHERE `id` = 'TTS_HuoshanDoubleStreamTTS';

UPDATE `ai_model_config`
SET `is_enabled` = 1
WHERE `id` = 'TTS_HSDSTTS_V2';
