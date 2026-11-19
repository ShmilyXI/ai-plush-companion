-- 客户端事件：网页端上报的关键行为，例如麦克风打开失败、重连失败、触发休息提醒。
-- 只有事件名和少量属性，不含对话内容，用于内测期间发现体验问题。
CREATE TABLE `ai_client_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_date` date NOT NULL COMMENT '发生日期（Asia/Shanghai）',
  `user_id` bigint NOT NULL COMMENT '用户',
  `name` varchar(40) NOT NULL COMMENT '事件名，小写字母和下划线',
  `props` varchar(500) DEFAULT NULL COMMENT '事件属性，JSON',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_client_event_day` (`event_date`, `name`),
  KEY `idx_ai_client_event_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端事件';
