UPDATE `ai_agent`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_agent_template`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_voice_clone`
SET `model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_tts_voice`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_companion_profile_model`
SET `resource_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `source_type` = 'global'
  AND `resource_id` = 'TTS_HSDSTTS_V2';

DELETE old_credential
FROM `ai_companion_global_model_credential` old_credential
JOIN `ai_companion_global_model_credential` unified_credential
  ON unified_credential.`user_id` = old_credential.`user_id`
 AND unified_credential.`global_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE old_credential.`global_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_companion_global_model_credential`
SET `global_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `global_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET `is_enabled` = 0,
    `is_default` = 0
WHERE `id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET `model_name` = '火山引擎语音合成',
    `config_json` = JSON_SET(
        COALESCE(`config_json`, JSON_OBJECT()),
        '$.type', 'huoshan_double_stream',
        '$.ws_url', 'wss://openspeech.bytedance.com/api/v3/tts/bidirection',
        '$.resource_id', CASE
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')) = 'seed-tts-2.0'
                THEN 'seed-tts-2.0'
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
