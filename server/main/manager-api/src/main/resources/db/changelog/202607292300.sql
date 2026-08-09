ALTER TABLE `ai_ota`
    ADD COLUMN `normalized_type_key` VARCHAR(255)
        GENERATED ALWAYS AS (
            LOWER(
                CASE
                    WHEN TRIM(COALESCE(`type`, '')) = '' THEN '__default__'
                    ELSE TRIM(`type`)
                END
            )
        ) STORED
        COMMENT '固件类型规范化唯一槽位',
    ADD UNIQUE KEY `uk_ai_ota_normalized_type` (`normalized_type_key`);
