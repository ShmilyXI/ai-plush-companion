-- 用户隔了多久再打开时，角色主动说一句开场白。
INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202611201200, 'app.opening_gap_hours', '4', 'number', 1, '距离上次聊天超过多少小时，用户再打开时角色主动开口；0 表示关闭'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'app.opening_gap_hours'
);

ALTER TABLE `ai_conversation_usage`
  MODIFY COLUMN `input_mode` varchar(16) NOT NULL DEFAULT 'text' COMMENT '输入方式(text/audio；opening 是角色主动说的开场白，不占每日次数)';

ALTER TABLE `ai_conversation_turn`
  MODIFY COLUMN `user_text` text NOT NULL COMMENT '用户说的话；角色主动说的开场白这一栏为空';
