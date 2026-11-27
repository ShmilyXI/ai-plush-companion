-- 年糕默认音色从 EdgeTTS 的晓伊改为豆包语音 SeedTTS 2.0 的甜美悦悦（TTS_DoubaoSeedTTS2_029，甜美软糯的甜妹音）。
-- 网页端现在只开放豆包语音的音色，留在 Edge 上的年糕在角色设置里已经选不回原来的声音。
-- 只改仍使用 Edge 默认音色（晓伊 TTS_EdgeTTS0003，或更早的辽宁小贝 TTS_EdgeTTS0007）的模板和角色；用户自己换过音色的不动。
-- 豆包语音没启用或这个音色不存在的部署不改。
UPDATE `ai_agent_template`
SET `tts_model_id` = 'TTS_DoubaoSeedTTS2', `tts_voice_id` = 'TTS_DoubaoSeedTTS2_029'
WHERE `agent_code` = 'niangao-companion'
  AND `tts_model_id` = 'TTS_EdgeTTS'
  AND `tts_voice_id` IN ('TTS_EdgeTTS0003', 'TTS_EdgeTTS0007')
  AND EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = 'TTS_DoubaoSeedTTS2' AND `model_type` = 'TTS' AND `is_enabled` = 1)
  AND EXISTS (SELECT 1 FROM `ai_tts_voice` WHERE `id` = 'TTS_DoubaoSeedTTS2_029' AND `tts_model_id` = 'TTS_DoubaoSeedTTS2');
UPDATE `ai_agent`
SET `tts_model_id` = 'TTS_DoubaoSeedTTS2', `tts_voice_id` = 'TTS_DoubaoSeedTTS2_029'
WHERE `companion_template_id` = 'template-niangao'
  AND `tts_model_id` = 'TTS_EdgeTTS'
  AND `tts_voice_id` IN ('TTS_EdgeTTS0003', 'TTS_EdgeTTS0007')
  AND EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = 'TTS_DoubaoSeedTTS2' AND `model_type` = 'TTS' AND `is_enabled` = 1)
  AND EXISTS (SELECT 1 FROM `ai_tts_voice` WHERE `id` = 'TTS_DoubaoSeedTTS2_029' AND `tts_model_id` = 'TTS_DoubaoSeedTTS2');
