-- liquibase formatted sql

-- changeset Codex:202607291600
ALTER TABLE `ai_agent_template`
    ADD COLUMN `companion_cue_config` TEXT DEFAULT NULL COMMENT '陪伴提示音效配置' AFTER `system_prompt`;

UPDATE `ai_agent_template`
SET `companion_cue_config` = '{"laugh":"config/assets/companion/laugh.wav","sigh":"config/assets/companion/sigh.wav","hesitate":"config/assets/companion/hesitate.wav","breathe":"config/assets/companion/breathe.wav"}'
WHERE `agent_code` = 'xiaozhi-companion'
  AND `companion_cue_config` IS NULL;
