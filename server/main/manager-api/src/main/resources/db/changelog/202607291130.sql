-- liquibase formatted sql

-- changeset Codex:202607291130
INSERT INTO `ai_companion_plan`
    (`id`, `plan_code`, `plan_name`, `max_devices`, `max_profiles`, `long_term_memory`, `advanced_voice`, `status`)
VALUES
    ('basic', 'basic', 'Basic', 1, 3, 1, 0, 1);
