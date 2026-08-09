CREATE TABLE `ai_companion_private_model` (
    `id` varchar(32) NOT NULL COMMENT '私有模型ID',
    `user_id` bigint NOT NULL COMMENT '所属用户ID',
    `model_type` varchar(20) NOT NULL COMMENT '模型类型',
    `name` varchar(64) NOT NULL COMMENT '显示名称',
    `provider_code` varchar(50) NOT NULL COMMENT '供应器编码',
    `api_url` varchar(500) DEFAULT NULL COMMENT 'API地址',
    `api_key_ciphertext` text COMMENT '加密后的API Key',
    `model_id` varchar(160) DEFAULT NULL COMMENT '供应器模型ID',
    `config_json` json DEFAULT NULL COMMENT '公共非敏感参数',
    `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用',
    `creator` bigint DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updater` bigint DEFAULT NULL,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_companion_private_model_owner_type` (`user_id`,`model_type`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户私有模型资源';

CREATE TABLE `ai_companion_profile_model` (
    `id` varchar(32) NOT NULL COMMENT '角色模型绑定ID',
    `agent_id` varchar(32) NOT NULL COMMENT '角色ID',
    `model_type` varchar(20) NOT NULL COMMENT '模型类型',
    `source_type` varchar(16) NOT NULL COMMENT 'default/global/private',
    `resource_id` varchar(32) DEFAULT NULL COMMENT '资源ID',
    `override_json` json DEFAULT NULL COMMENT '角色专用非敏感参数',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_companion_profile_model_type` (`agent_id`,`model_type`),
    KEY `idx_companion_profile_model_resource` (`source_type`,`resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪伴角色模型绑定';
