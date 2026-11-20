-- 用户同意记录：用户在登录时确认已满 18 周岁并同意用户协议和隐私政策，记下同意的是哪一版、什么时候。
CREATE TABLE `ai_user_consent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户',
  `version` varchar(32) NOT NULL COMMENT '协议版本，例如 2026-11-20',
  `agreed_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '同意时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_user_consent` (`user_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户协议同意记录';
