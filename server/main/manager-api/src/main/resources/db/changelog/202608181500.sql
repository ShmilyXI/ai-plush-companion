CREATE TABLE `ai_skill_package` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `version_no` int NOT NULL,
  `package_sha256` char(64) NOT NULL,
  `package_size` bigint NOT NULL,
  `storage_key` varchar(500) NOT NULL,
  `manifest_json` longtext NOT NULL,
  `skill_markdown` longtext NOT NULL,
  `source_type` varchar(24) NOT NULL,
  `validation_status` varchar(24) NOT NULL,
  `validation_report_json` longtext NOT NULL,
  `published` tinyint NOT NULL DEFAULT 0,
  `creator` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_package_version` (`capability_id`,`version_no`),
  KEY `idx_skill_package_published` (`capability_id`,`published`,`version_no`),
  CONSTRAINT `fk_skill_package_capability` FOREIGN KEY (`capability_id`)
    REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
