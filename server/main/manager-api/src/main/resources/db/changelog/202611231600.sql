-- 中英双语音色。普通话音色念英文有口音，英语学习角色（枫糖）用这个：中英混着念都自然。
-- 音色名在 EdgeTTS 官方列表里是 en-US-EmmaMultilingualNeural（女声，Cheerful, Clear, Conversational）。
-- 写明列名：ai_tts_voice 后来加过 reference_audio、reference_text 两列，按位置插入会对不上。
delete from `ai_tts_voice` where id = 'TTS_EdgeTTS0012';
INSERT INTO `ai_tts_voice` (`id`, `tts_model_id`, `name`, `tts_voice`, `languages`, `voice_demo`, `remark`, `sort`) VALUES
('TTS_EdgeTTS0012', 'TTS_EdgeTTS', 'EdgeTTS女声-艾玛（中英双语）', 'en-US-EmmaMultilingualNeural', '中英双语', 'General', 'Cheerful, Clear, Conversational', 12);
