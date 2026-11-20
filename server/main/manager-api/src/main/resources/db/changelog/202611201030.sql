-- 安全事件：内容安全检查命中时留一条记录，供运营复核。只记类别和处理方式，不记对话内容。
CREATE TABLE `ai_safety_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户',
  `agent_id` varchar(32) NOT NULL COMMENT '角色',
  `conversation_id` varchar(64) NOT NULL COMMENT '会话',
  `turn_id` varchar(64) DEFAULT NULL COMMENT '会话内的轮次标识',
  `direction` varchar(8) NOT NULL COMMENT '命中的是用户输入(input)还是模型输出(output)',
  `action` varchar(16) NOT NULL COMMENT '处理方式：block 拦截，crisis 给出求助信息',
  `category` varchar(32) NOT NULL COMMENT '类别，例如 self_harm、illegal_instructions',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_safety_event_time` (`created_at`),
  KEY `idx_ai_safety_event_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容安全事件';

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202611201030, 'app.safety_blocked_terms', '', 'string', 1, '追加的违禁词，用英文逗号或换行分隔，输入和输出都会检查'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'app.safety_blocked_terms'
);
