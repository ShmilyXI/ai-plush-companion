-- 中英双语音色。普通话音色念英文有口音，英语学习角色（枫糖）用这个：中英混着念都自然。
-- 音色名在 EdgeTTS 官方列表里是 en-US-EmmaMultilingualNeural（女声，Cheerful, Clear, Conversational）。
delete from `ai_tts_voice` where id = 'TTS_EdgeTTS0012';
INSERT INTO `ai_tts_voice` VALUES
('TTS_EdgeTTS0012', 'TTS_EdgeTTS', 'EdgeTTS女声-艾玛（中英双语）', 'en-US-EmmaMultilingualNeural', '中英双语', NULL, 'Cheerful, Clear, Conversational', 12, NULL, NULL, NULL, NULL);
