-- liquibase formatted sql
-- changeset Codex:202608132300
CREATE TABLE `ai_device_wake_word` (
  `device_id` VARCHAR(32) NOT NULL,
  `desired_word` VARCHAR(32) DEFAULT NULL,
  `desired_version` BIGINT NOT NULL DEFAULT 0,
  `active_word` VARCHAR(32) DEFAULT NULL,
  `active_version` BIGINT NOT NULL DEFAULT 0,
  `candidate_path` VARCHAR(512) DEFAULT NULL,
  `candidate_token` VARCHAR(64) DEFAULT NULL,
  `candidate_sha256` CHAR(64) DEFAULT NULL,
  `candidate_size` BIGINT DEFAULT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'IDLE',
  `last_error_code` VARCHAR(64) DEFAULT NULL,
  `last_error_message` VARCHAR(512) DEFAULT NULL,
  `capable` TINYINT NOT NULL DEFAULT 0,
  `capability_reason` VARCHAR(128) DEFAULT NULL,
  `chip_model` VARCHAR(32) DEFAULT NULL,
  `assets_partition_size` BIGINT DEFAULT NULL,
  `layout_version` INT DEFAULT NULL,
  `slot_size` BIGINT DEFAULT NULL,
  `lock_token` VARCHAR(64) DEFAULT NULL,
  `lock_until` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`device_id`),
  UNIQUE KEY `uk_device_wake_word_candidate_token` (`candidate_token`),
  CONSTRAINT `fk_device_wake_word_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
