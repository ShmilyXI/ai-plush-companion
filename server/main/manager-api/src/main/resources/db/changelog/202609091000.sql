-- Companion cue sound effects have been removed from the runtime; drop the persisted cue config columns.
ALTER TABLE `ai_agent`
    DROP COLUMN `companion_cue_config`;

ALTER TABLE `ai_agent_template`
    DROP COLUMN `companion_cue_config`;
