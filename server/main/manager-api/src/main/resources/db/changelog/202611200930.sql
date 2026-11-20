-- 对话记录：按“用户 + 角色”保存每一轮对话的文字，新会话用它接上之前聊过的内容。
-- 只保存第一方登录用户的对话；通过 API Key 接入的对话不写入这张表。
CREATE TABLE `ai_conversation_turn` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键，同时决定先后顺序',
  `user_id` bigint NOT NULL COMMENT '用户',
  `agent_id` varchar(32) NOT NULL COMMENT '角色',
  `conversation_id` varchar(64) NOT NULL COMMENT '产生这一轮的会话',
  `turn_id` varchar(64) NOT NULL COMMENT '会话内的轮次标识',
  `user_text` text NOT NULL COMMENT '用户说的话',
  `reply_text` text NOT NULL COMMENT '角色的回复',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_conversation_turn` (`conversation_id`, `turn_id`),
  KEY `idx_ai_conversation_turn_thread` (`user_id`, `agent_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记录';
