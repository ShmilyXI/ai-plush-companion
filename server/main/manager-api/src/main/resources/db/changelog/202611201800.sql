-- 角色记得的关于用户的事。每条是一句话，按“用户 + 角色”保存，用户可以逐条查看和删除。
CREATE TABLE IF NOT EXISTS `ai_conversation_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户',
  `agent_id` varchar(32) NOT NULL COMMENT '角色',
  `content` varchar(300) NOT NULL COMMENT '记住的一件事',
  `source` varchar(16) NOT NULL DEFAULT 'conversation' COMMENT '来源(conversation 是从对话里记下的，user 是用户自己写的)',
  `created_at` datetime NOT NULL COMMENT '记下的时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_memory_owner` (`user_id`, `agent_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色记得的关于用户的事';
