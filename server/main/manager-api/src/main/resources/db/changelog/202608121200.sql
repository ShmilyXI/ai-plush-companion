UPDATE `ai_model_config`
SET `model_name` = '火山引擎语音合成',
    `config_json` = JSON_SET(
        COALESCE(`config_json`, JSON_OBJECT()),
        '$.type', 'huoshan_double_stream',
        '$.ws_url', COALESCE(
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.ws_url')), ''),
            'wss://openspeech.bytedance.com/api/v3/tts/bidirection'
        ),
        '$.resource_id', CASE
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')) = 'seed-tts-2.0'
                THEN 'seed-tts-2.0'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')) = 'volc.service_type.10029'
                THEN 'seed-tts-1.0'
            WHEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')), '') IS NOT NULL
                THEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id'))
            ELSE 'seed-tts-1.0'
        END,
        '$.access_key_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.access_key_id')), ''),
        '$.secret_access_key', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.secret_access_key')), '')
    ),
    `doc_link` = 'https://docs.volcengine.com/docs/6561/2160690?lang=zh'
WHERE `id` = 'TTS_HuoshanDoubleStreamTTS';

UPDATE `ai_model_provider`
SET `name` = '火山引擎',
    `fields` = '[{"key":"ws_url","label":"WebSocket地址","type":"string","default":"wss://openspeech.bytedance.com/api/v3/tts/bidirection"},{"key":"appid","label":"应用ID","type":"string"},{"key":"access_token","label":"访问令牌","type":"password"},{"key":"resource_id","label":"模型版本","type":"string","options":[{"label":"语音合成 1.0","value":"seed-tts-1.0"},{"label":"语音合成 2.0","value":"seed-tts-2.0"}],"default":"seed-tts-1.0"},{"key":"access_key_id","label":"Access Key ID","type":"string"},{"key":"secret_access_key","label":"Secret Access Key","type":"password"},{"key":"speaker","label":"默认音色","type":"string"},{"key":"enable_ws_reuse","label":"是否开启链接复用","type":"boolean","default":true},{"key":"audio_params","label":"音频参数","type":"dict","default":{}},{"key":"additions","label":"附加参数","type":"dict","default":{}},{"key":"mix_speaker","label":"混音配置","type":"dict","default":{}}]'
WHERE `id` = 'SYSTEM_TTS_HSDSTTS';
