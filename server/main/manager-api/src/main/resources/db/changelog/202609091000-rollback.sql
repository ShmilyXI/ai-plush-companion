ALTER TABLE `ai_agent`
    ADD COLUMN `companion_cue_config` TEXT DEFAULT NULL COMMENT '陪伴提示配置' AFTER `companion_template_id`;

ALTER TABLE `ai_agent_template`
    ADD COLUMN `companion_cue_config` TEXT DEFAULT NULL COMMENT '陪伴提示音效配置' AFTER `system_prompt`;
