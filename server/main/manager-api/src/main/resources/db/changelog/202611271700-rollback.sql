-- 回到 EdgeTTS 的晓伊。只改仍是这次换上的甜美悦悦的模板和角色。
UPDATE `ai_agent_template`
SET `tts_model_id` = 'TTS_EdgeTTS', `tts_voice_id` = 'TTS_EdgeTTS0003'
WHERE `agent_code` = 'niangao-companion'
  AND `tts_model_id` = 'TTS_DoubaoSeedTTS2'
  AND `tts_voice_id` = 'TTS_DoubaoSeedTTS2_029'
  AND EXISTS (SELECT 1 FROM `ai_tts_voice` WHERE `id` = 'TTS_EdgeTTS0003' AND `tts_model_id` = 'TTS_EdgeTTS');
UPDATE `ai_agent`
SET `tts_model_id` = 'TTS_EdgeTTS', `tts_voice_id` = 'TTS_EdgeTTS0003'
WHERE `companion_template_id` = 'template-niangao'
  AND `tts_model_id` = 'TTS_DoubaoSeedTTS2'
  AND `tts_voice_id` = 'TTS_DoubaoSeedTTS2_029'
  AND EXISTS (SELECT 1 FROM `ai_tts_voice` WHERE `id` = 'TTS_EdgeTTS0003' AND `tts_model_id` = 'TTS_EdgeTTS');
