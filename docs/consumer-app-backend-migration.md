# Consumer app backend migration

The consumer app uses the new `/app/*` and `/api/v1/conversations` routes. Existing `/user/*`, device MQTT traffic, and hardware memory request shapes remain available during rollout.

The `ai_app_contact`, `ai_app_auth_challenge`, and `ai_app_refresh_token` tables store normalized contacts, HMAC/BCrypt-derived values, expiry timestamps, and revocation state. Plaintext verification codes and refresh tokens are never persisted. The `ai_agent` migration adds `memory_enabled`, `consumer_deleted_at`, and `avatar_url`; profile deletion is a soft delete and is rejected while a device references the profile.

The durable conversation index uses `ai_companion_conversation` and `ai_companion_conversation_turn`. A turn is idempotent on `(conversation_id, turn_id)`, contains text transcription only, and has an explicit `source` of `app` or `device`. Deleting a conversation hides its text history and does not delete profile memory.

Canonical profile memory is addressed as `companion:<userId>:<profileId>`. Hardware configuration still includes its legacy device-specific `memory_namespace` for old clients and additionally supplies `profile_memory_namespace`; the Python runtime prefers the canonical field when present. The App memory endpoint requires user ID, profile ID, and an exact matching namespace, so a MAC address cannot be used to impersonate another profile. Existing device memory endpoints continue to use the server-secret boundary and their original request fields.

Migration is additive. A failed canonical memory write leaves the legacy namespace intact and can be retried. Rollback scripts drop only the new tables/columns; they do not touch `sys_user`, `sys_user_token`, device credentials, NVS data, or firmware images.
