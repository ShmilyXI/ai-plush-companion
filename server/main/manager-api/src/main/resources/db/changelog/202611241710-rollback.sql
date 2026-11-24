-- 回到 202611241500 登记的 14 个音色（没有试听地址和场景）。
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
ALTER TABLE `ai_tts_voice` MODIFY COLUMN `name` varchar(20) DEFAULT NULL COMMENT '音色名称';
