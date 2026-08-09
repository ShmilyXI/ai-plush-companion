-- liquibase formatted sql

-- changeset Codex:202607282300
ALTER TABLE `ai_agent`
    ADD COLUMN `companion_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用陪伴功能(0关闭/1开启)' AFTER `system_prompt`,
    ADD COLUMN `relation_mode` VARCHAR(16) NOT NULL DEFAULT 'friend' COMMENT '关系模式' AFTER `companion_enabled`,
    ADD COLUMN `user_address` VARCHAR(64) DEFAULT NULL COMMENT '对用户的称呼' AFTER `relation_mode`,
    ADD COLUMN `personality` VARCHAR(1000) DEFAULT NULL COMMENT '角色性格' AFTER `user_address`,
    ADD COLUMN `companion_template_id` VARCHAR(64) DEFAULT NULL COMMENT '陪伴模板ID' AFTER `personality`,
    ADD COLUMN `companion_cue_config` TEXT DEFAULT NULL COMMENT '陪伴提示配置' AFTER `companion_template_id`,
    ADD COLUMN `screen_expression_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用屏幕表情(0关闭/1开启)' AFTER `companion_cue_config`,
    ADD COLUMN `camera_preference_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用摄像头偏好(0关闭/1开启)' AFTER `screen_expression_enabled`;

ALTER TABLE `ai_device`
    ADD COLUMN `has_display` TINYINT NOT NULL DEFAULT 0 COMMENT '是否有显示屏(0否/1是)' AFTER `app_version`,
    ADD COLUMN `has_camera` TINYINT NOT NULL DEFAULT 0 COMMENT '是否有摄像头(0否/1是)' AFTER `has_display`;

CREATE TABLE IF NOT EXISTS `ai_companion_plan` (
    `id` VARCHAR(32) NOT NULL COMMENT '套餐ID',
    `plan_code` VARCHAR(32) NOT NULL COMMENT '套餐编码',
    `plan_name` VARCHAR(64) NOT NULL COMMENT '套餐名称',
    `max_devices` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最大设备数',
    `max_profiles` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '最大角色数',
    `long_term_memory` TINYINT NOT NULL DEFAULT 0 COMMENT '长期记忆能力(0关闭/1开启)',
    `advanced_voice` TINYINT NOT NULL DEFAULT 0 COMMENT '高级语音能力(0关闭/1开启)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态(0停用/1启用)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_companion_plan_code` (`plan_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪伴订阅套餐表';

CREATE TABLE IF NOT EXISTS `ai_companion_subscription` (
    `id` VARCHAR(32) NOT NULL COMMENT '订阅ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `plan_id` VARCHAR(32) NOT NULL COMMENT '套餐ID',
    `status` VARCHAR(16) NOT NULL COMMENT '订阅状态',
    `starts_at` DATETIME NOT NULL COMMENT '开始时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '到期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `active_user_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'active' THEN `user_id` ELSE NULL END) STORED COMMENT '激活订阅用户唯一键',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_companion_subscription_active_user` (`active_user_id`),
    INDEX `idx_companion_subscription_user_status` (`user_id`, `status`),
    INDEX `idx_companion_subscription_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪伴订阅表';

CREATE TABLE IF NOT EXISTS `ai_companion_audit` (
    `id` VARCHAR(32) NOT NULL COMMENT '审计记录ID',
    `operator_id` BIGINT NOT NULL COMMENT '操作人ID',
    `target_user_id` BIGINT DEFAULT NULL COMMENT '目标用户ID',
    `action` VARCHAR(64) NOT NULL COMMENT '操作动作',
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `resource_id` VARCHAR(64) DEFAULT NULL COMMENT '资源ID',
    `summary` VARCHAR(1000) NOT NULL COMMENT '脱敏摘要，不含密码、令牌或完整记忆',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_companion_audit_target_created` (`target_user_id`, `created_at`),
    INDEX `idx_companion_audit_operator_created` (`operator_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪伴后台审计表';
