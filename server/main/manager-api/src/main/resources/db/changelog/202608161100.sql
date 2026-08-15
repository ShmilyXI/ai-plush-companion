CREATE TABLE `ai_capability` (
  `id` varchar(32) NOT NULL,
  `capability_code` varchar(64) NOT NULL,
  `type` varchar(32) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'DRAFT',
  `draft_version` int NOT NULL DEFAULT 1,
  `published_version` int DEFAULT NULL,
  `creator` bigint DEFAULT NULL,
  `updater` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capability_code` (`capability_code`),
  KEY `idx_capability_type_status` (`type`,`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_capability_version` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `version_no` int NOT NULL,
  `content_json` longtext NOT NULL,
  `content_sha256` char(64) NOT NULL,
  `publisher` bigint DEFAULT NULL,
  `published_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capability_version` (`capability_id`,`version_no`),
  KEY `idx_capability_version_published` (`capability_id`,`published_at`),
  CONSTRAINT `fk_capability_version_capability` FOREIGN KEY (`capability_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_skill_definition` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `execution_prompt` longtext NOT NULL,
  `trigger_mode` varchar(16) NOT NULL DEFAULT 'MIXED',
  `rule_mode` varchar(16) NOT NULL DEFAULT 'ANY',
  `semantic_threshold` decimal(5,4) NOT NULL DEFAULT 0.7000,
  `response_mode` varchar(16) NOT NULL DEFAULT 'LLM',
  `timeout_ms` int NOT NULL DEFAULT 30000,
  `failure_message` varchar(500) DEFAULT NULL,
  `overridable_fields_json` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_definition_capability` (`capability_id`),
  CONSTRAINT `fk_skill_definition_capability` FOREIGN KEY (`capability_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_skill_trigger` (
  `id` bigint NOT NULL,
  `skill_id` varchar(32) NOT NULL,
  `trigger_type` varchar(24) NOT NULL,
  `pattern_text` longtext NOT NULL,
  `priority` int NOT NULL DEFAULT 0,
  `case_sensitive` tinyint NOT NULL DEFAULT 0,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_trigger_lookup` (`skill_id`,`trigger_type`,`enabled`,`priority`),
  CONSTRAINT `fk_skill_trigger_skill` FOREIGN KEY (`skill_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_skill_tool_mapping` (
  `id` bigint NOT NULL,
  `skill_id` varchar(32) NOT NULL,
  `tool_type` varchar(24) NOT NULL,
  `tool_ref_id` varchar(32) NOT NULL,
  `tool_name` varchar(128) NOT NULL,
  `tool_alias` varchar(128) DEFAULT NULL,
  `purpose` varchar(500) DEFAULT NULL,
  `default_params_json` json DEFAULT NULL,
  `required` tinyint NOT NULL DEFAULT 0,
  `sort_order` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_tool` (`skill_id`,`tool_type`,`tool_ref_id`,`tool_name`),
  KEY `idx_skill_tool_ref` (`tool_type`,`tool_ref_id`),
  CONSTRAINT `fk_skill_tool_skill` FOREIGN KEY (`skill_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SELECT CHARACTER_SET_NAME, COLLATION_NAME
INTO @capability_device_id_charset, @capability_device_id_collation
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_device'
  AND COLUMN_NAME = 'id';

SET @device_skill_ddl = CONCAT(
  'CREATE TABLE `ai_device_skill_mapping` (',
  '`id` bigint NOT NULL,',
  '`device_id` varchar(32) CHARACTER SET ', @capability_device_id_charset,
  ' COLLATE ', @capability_device_id_collation, ' NOT NULL,',
  '`skill_id` varchar(32) NOT NULL,',
  '`version_mode` varchar(16) NOT NULL DEFAULT ''LATEST'',',
  '`fixed_version` int DEFAULT NULL,',
  '`enabled` tinyint NOT NULL DEFAULT 1,',
  '`override_json` json DEFAULT NULL,',
  '`trigger_priority` int NOT NULL DEFAULT 0,',
  '`config_version` bigint NOT NULL DEFAULT 1,',
  '`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,',
  '`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,',
  'PRIMARY KEY (`id`),',
  'UNIQUE KEY `uk_device_skill` (`device_id`,`skill_id`),',
  'KEY `idx_device_skill_enabled` (`device_id`,`enabled`,`trigger_priority`),',
  'KEY `idx_device_skill_skill` (`skill_id`,`enabled`),',
  'CONSTRAINT `fk_device_skill_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE,',
  'CONSTRAINT `fk_device_skill_skill` FOREIGN KEY (`skill_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE',
  ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
);
PREPARE device_skill_stmt FROM @device_skill_ddl;
EXECUTE device_skill_stmt;
DEALLOCATE PREPARE device_skill_stmt;

CREATE TABLE `ai_plugin_definition` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `executor_name` varchar(128) NOT NULL,
  `input_schema_json` json NOT NULL,
  `config_schema_json` json DEFAULT NULL,
  `secret_fields_json` json DEFAULT NULL,
  `default_config_json` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plugin_capability` (`capability_id`),
  UNIQUE KEY `uk_plugin_executor` (`executor_name`),
  CONSTRAINT `fk_plugin_capability` FOREIGN KEY (`capability_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_mcp_server` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `transport` varchar(32) NOT NULL,
  `connection_config_json` json NOT NULL,
  `secret_refs_json` json DEFAULT NULL,
  `approved_command_template_json` json DEFAULT NULL,
  `health_status` varchar(24) NOT NULL DEFAULT 'UNKNOWN',
  `last_error` varchar(500) DEFAULT NULL,
  `last_checked_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_server_capability` (`capability_id`),
  KEY `idx_mcp_server_health` (`health_status`,`last_checked_at`),
  CONSTRAINT `fk_mcp_server_capability` FOREIGN KEY (`capability_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `ai_mcp_tool_snapshot` (
  `id` varchar(32) NOT NULL,
  `mcp_server_id` varchar(32) NOT NULL,
  `tool_name` varchar(128) NOT NULL,
  `input_schema_json` json NOT NULL,
  `schema_sha256` char(64) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'DISCOVERED',
  `approved` tinyint NOT NULL DEFAULT 0,
  `synced_at` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_tool_name` (`mcp_server_id`,`tool_name`),
  KEY `idx_mcp_tool_status` (`mcp_server_id`,`approved`,`status`),
  CONSTRAINT `fk_mcp_tool_server` FOREIGN KEY (`mcp_server_id`) REFERENCES `ai_mcp_server` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @device_tool_ddl = CONCAT(
  'CREATE TABLE `ai_device_tool_snapshot` (',
  '`id` varchar(32) NOT NULL,',
  '`device_id` varchar(32) CHARACTER SET ', @capability_device_id_charset,
  ' COLLATE ', @capability_device_id_collation, ' NOT NULL,',
  '`tool_name` varchar(128) NOT NULL,',
  '`input_schema_json` json NOT NULL,',
  '`schema_sha256` char(64) NOT NULL,',
  '`device_model` varchar(64) DEFAULT NULL,',
  '`firmware_version` varchar(64) DEFAULT NULL,',
  '`available` tinyint NOT NULL DEFAULT 1,',
  '`last_seen_at` datetime NOT NULL,',
  '`created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,',
  '`updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,',
  'PRIMARY KEY (`id`),',
  'UNIQUE KEY `uk_device_tool_name` (`device_id`,`tool_name`),',
  'KEY `idx_device_tool_seen` (`device_id`,`available`,`last_seen_at`),',
  'KEY `idx_device_tool_firmware` (`device_model`,`firmware_version`),',
  'CONSTRAINT `fk_device_tool_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE',
  ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
);
PREPARE device_tool_stmt FROM @device_tool_ddl;
EXECUTE device_tool_stmt;
DEALLOCATE PREPARE device_tool_stmt;

CREATE TABLE `ai_capability_secret` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `secret_name` varchar(128) NOT NULL,
  `secret_ciphertext` longtext NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capability_secret_name` (`capability_id`,`secret_name`),
  KEY `idx_capability_secret_capability` (`capability_id`),
  CONSTRAINT `fk_capability_secret_capability` FOREIGN KEY (`capability_id`) REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
