-- 邀请码：封闭内测期间，新手机号必须凭邀请码才能创建账号，已有账号不受影响。
CREATE TABLE `ai_invite_code` (
  `id` varchar(32) NOT NULL COMMENT '主键',
  `code` varchar(32) NOT NULL COMMENT '邀请码',
  `max_uses` int unsigned NOT NULL DEFAULT '1' COMMENT '可使用次数',
  `used_count` int unsigned NOT NULL DEFAULT '0' COMMENT '已使用次数',
  `expire_date` datetime DEFAULT NULL COMMENT '失效时间，空表示不过期',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态(0停用/1启用)',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注，例如发给了谁',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `create_date` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_invite_code_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码';

CREATE TABLE `ai_invite_code_use` (
  `id` varchar(32) NOT NULL COMMENT '主键',
  `code_id` varchar(32) NOT NULL COMMENT '邀请码主键',
  `user_id` bigint NOT NULL COMMENT '使用邀请码创建的账号',
  `used_at` datetime NOT NULL COMMENT '使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_invite_code_use_user` (`user_id`),
  KEY `idx_ai_invite_code_use_code` (`code_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码使用记录';

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202611200900, 'app.invite_required', 'true', 'boolean', 1, '新手机号创建账号时是否必须填写邀请码'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'app.invite_required'
);
