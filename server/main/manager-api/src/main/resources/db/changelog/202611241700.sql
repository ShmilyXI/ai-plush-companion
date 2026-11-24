-- 音色按场景分组显示（通用场景、角色扮演、客服场景……）。一个音色只放在一个主场景里。
ALTER TABLE `ai_tts_voice` ADD COLUMN `scene` varchar(64) DEFAULT NULL COMMENT '场景分组，例如 通用场景、角色扮演' AFTER `remark`;
