ALTER TABLE `ai_device`
  ADD COLUMN `companion_mode` varchar(32) NOT NULL DEFAULT 'turn_based' COMMENT '陪伴模式(turn_based/proactive)';
