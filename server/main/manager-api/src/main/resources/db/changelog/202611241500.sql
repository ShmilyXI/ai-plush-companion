-- 豆包语音 SeedTTS 2.0：走 OpenAI 兼容的 /audio/speech 网关，模型 seed-tts-2.0。
-- 运行时是 zixuan-server 的 core/providers/tts/doubao_seedtts.py。
-- 网关地址和密钥只放在 zixuan-server 的环境变量 ZIXUAN_DOUBAO_TTS_API_URL、ZIXUAN_DOUBAO_TTS_API_KEY，
-- 不写进这里，也不进管理台、浏览器和设备。供应器字段里没有凭据类字段，
-- 所以角色可以直接选这个模型（凭据要求按“无需配置”处理），角色覆盖项也改不了密钥发往的地址。
-- 音色都是 SeedTTS 2.0 的音色（*_uranus_bigtts），1.0 的 *_moon_bigtts 和这个资源 ID 不匹配。
delete from `ai_model_provider` where id = 'SYSTEM_TTS_doubao_seedtts';
INSERT INTO `ai_model_provider` (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('SYSTEM_TTS_doubao_seedtts', 'TTS', 'doubao_seedtts', '豆包语音 SeedTTS 2.0',
 '[{"key": "model", "type": "string", "label": "模型"}, {"key": "voice", "type": "string", "label": "默认音色"}, {"key": "speed", "type": "number", "label": "语速"}, {"key": "format", "type": "string", "label": "音频格式"}, {"key": "output_dir", "type": "string", "label": "输出目录"}]',
 21, 1, NOW(), 1, NOW());

delete from `ai_model_config` where id = 'TTS_DoubaoSeedTTS2';
INSERT INTO `ai_model_config` (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('TTS_DoubaoSeedTTS2', 'TTS', 'DoubaoSeedTTS2', '豆包语音 SeedTTS 2.0', 0, 1,
 '{"type": "doubao_seedtts", "model": "seed-tts-2.0", "voice": "zh_female_vv_uranus_bigtts", "speed": 1, "format": "mp3", "output_dir": "tmp/"}',
 'https://www.volcengine.com/docs/6561/1257544',
 '豆包语音 SeedTTS 2.0（OpenAI 兼容网关）。网关地址和密钥在 zixuan-server 的环境变量 ZIXUAN_DOUBAO_TTS_API_URL、ZIXUAN_DOUBAO_TTS_API_KEY 里配置，不在这里填。音色只能用 SeedTTS 2.0 的 *_uranus_bigtts。',
 24, 1, NOW(), 1, NOW());

-- 写明列名：ai_tts_voice 后来加过 reference_audio、reference_text 两列，按位置插入会对不上。
delete from `ai_tts_voice` where tts_model_id = 'TTS_DoubaoSeedTTS2';
INSERT INTO `ai_tts_voice` (`id`, `tts_model_id`, `name`, `tts_voice`, `languages`, `voice_demo`, `remark`, `sort`) VALUES
('TTS_DoubaoSeedTTS2_01', 'TTS_DoubaoSeedTTS2', '豆包女声-Vivi', 'zh_female_vv_uranus_bigtts', '中文', NULL, '通用女声，自然亲切', 1),
('TTS_DoubaoSeedTTS2_02', 'TTS_DoubaoSeedTTS2', '豆包女声-小何', 'zh_female_xiaohe_uranus_bigtts', '中文', NULL, '清亮女声', 2),
('TTS_DoubaoSeedTTS2_03', 'TTS_DoubaoSeedTTS2', '豆包女声-灿灿', 'zh_female_cancan_uranus_bigtts', '中文', NULL, '活泼女声', 3),
('TTS_DoubaoSeedTTS2_04', 'TTS_DoubaoSeedTTS2', '豆包女声-爽快思思', 'zh_female_shuangkuaisisi_uranus_bigtts', '中文', NULL, '爽快女声', 4),
('TTS_DoubaoSeedTTS2_05', 'TTS_DoubaoSeedTTS2', '豆包女声-甜美小源', 'zh_female_tianmeixiaoyuan_uranus_bigtts', '中文', NULL, '甜美女声', 5),
('TTS_DoubaoSeedTTS2_06', 'TTS_DoubaoSeedTTS2', '豆包女声-温柔小雅', 'zh_female_wenrouxiaoya_uranus_bigtts', '中文', NULL, '温柔女声', 6),
('TTS_DoubaoSeedTTS2_07', 'TTS_DoubaoSeedTTS2', '豆包女声-邻家女孩', 'zh_female_linjianvhai_uranus_bigtts', '中文', NULL, '邻家女声', 7),
('TTS_DoubaoSeedTTS2_08', 'TTS_DoubaoSeedTTS2', '豆包女声-开朗姐姐', 'zh_female_kailangjiejie_uranus_bigtts', '中文', NULL, '开朗女声', 8),
('TTS_DoubaoSeedTTS2_09', 'TTS_DoubaoSeedTTS2', '豆包女声-清新女声', 'zh_female_qingxinnvsheng_uranus_bigtts', '中文', NULL, '清新女声', 9),
('TTS_DoubaoSeedTTS2_10', 'TTS_DoubaoSeedTTS2', '豆包男声-云舟', 'zh_male_m191_uranus_bigtts', '中文', NULL, '通用男声', 10),
('TTS_DoubaoSeedTTS2_11', 'TTS_DoubaoSeedTTS2', '豆包男声-小天', 'zh_male_taocheng_uranus_bigtts', '中文', NULL, '沉稳男声', 11),
('TTS_DoubaoSeedTTS2_12', 'TTS_DoubaoSeedTTS2', '豆包男声-阳光青年', 'zh_male_yangguangqingnian_uranus_bigtts', '中文', NULL, '阳光男声', 12),
('TTS_DoubaoSeedTTS2_13', 'TTS_DoubaoSeedTTS2', '豆包男声-少年梓辛', 'zh_male_shaonianzixin_uranus_bigtts', '中文', NULL, '少年男声', 13),
('TTS_DoubaoSeedTTS2_14', 'TTS_DoubaoSeedTTS2', '豆包男声-温暖阿虎', 'zh_male_wennuanahu_uranus_bigtts', '中文', NULL, '温暖男声', 14);
