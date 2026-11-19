-- 对话用量：每完成一轮记一行，用于成本核算、每日额度和活跃统计。
-- 只记数量和耗时，不记对话内容；用户删除对话记录不影响这张表。
CREATE TABLE `ai_conversation_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `usage_date` date NOT NULL COMMENT '发生日期（Asia/Shanghai）',
  `user_id` bigint NOT NULL COMMENT '用户；API Key 会话记 Key 的所有者',
  `agent_id` varchar(32) NOT NULL COMMENT '角色',
  `conversation_id` varchar(64) NOT NULL COMMENT '会话',
  `turn_id` varchar(64) NOT NULL COMMENT '会话内的轮次标识',
  `first_party` tinyint NOT NULL DEFAULT '1' COMMENT '是否登录用户的会话(1)，还是 API Key 会话(0)',
  `input_mode` varchar(16) NOT NULL DEFAULT 'text' COMMENT '输入方式(text/audio)',
  `llm_model` varchar(128) DEFAULT NULL COMMENT '模型代号',
  `prompt_tokens` int DEFAULT NULL COMMENT '模型报告的输入 token，服务商不报告时为空',
  `completion_tokens` int DEFAULT NULL COMMENT '模型报告的输出 token，服务商不报告时为空',
  `input_chars` int NOT NULL DEFAULT '0' COMMENT '用户输入字数',
  `reply_chars` int NOT NULL DEFAULT '0' COMMENT '回复字数',
  `tts_chars` int NOT NULL DEFAULT '0' COMMENT '送去语音合成的字数',
  `first_text_ms` int DEFAULT NULL COMMENT '从收到输入到出现第一段文字的毫秒数',
  `total_ms` int DEFAULT NULL COMMENT '这一轮的总耗时毫秒数',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_conversation_usage` (`conversation_id`, `turn_id`),
  KEY `idx_ai_conversation_usage_day` (`usage_date`, `user_id`),
  KEY `idx_ai_conversation_usage_user` (`user_id`, `usage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话用量';

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202611201000, 'app.daily_turn_limit', '200', 'number', 1, '每个登录用户每天最多对话多少轮，0 表示不限制'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'app.daily_turn_limit'
);
