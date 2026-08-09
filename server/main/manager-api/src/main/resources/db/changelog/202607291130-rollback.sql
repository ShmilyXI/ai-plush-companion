SELECT `id` FROM `ai_companion_plan` WHERE `id` = 'basic' FOR UPDATE;

-- Duplicate an existing reference into the same primary key so rollback aborts before deleting the plan.
INSERT INTO `ai_companion_subscription`
    (`id`, `user_id`, `plan_id`, `status`, `starts_at`, `expires_at`, `created_at`, `updated_at`)
SELECT
    `id`, `user_id`, `plan_id`, `status`, `starts_at`, `expires_at`, `created_at`, `updated_at`
FROM `ai_companion_subscription`
WHERE `plan_id` = 'basic'
LIMIT 1;

-- Duplicate a modified seed into the same primary key so rollback cannot silently leave drifted data behind.
INSERT INTO `ai_companion_plan`
    (`id`, `plan_code`, `plan_name`, `max_devices`, `max_profiles`, `long_term_memory`, `advanced_voice`,
     `status`, `created_at`, `updated_at`)
SELECT
    `id`, `plan_code`, `plan_name`, `max_devices`, `max_profiles`, `long_term_memory`, `advanced_voice`,
    `status`, `created_at`, `updated_at`
FROM `ai_companion_plan`
WHERE `id` = 'basic'
  AND NOT (
      `plan_code` = 'basic'
      AND `plan_name` = 'Basic'
      AND `max_devices` = 1
      AND `max_profiles` = 3
      AND `long_term_memory` = 1
      AND `advanced_voice` = 0
      AND `status` = 1
  )
LIMIT 1;

DELETE FROM `ai_companion_plan`
WHERE `id` = 'basic'
  AND `plan_code` = 'basic'
  AND `plan_name` = 'Basic'
  AND `max_devices` = 1
  AND `max_profiles` = 3
  AND `long_term_memory` = 1
  AND `advanced_voice` = 0
  AND `status` = 1;
