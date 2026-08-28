# Consumer App Contract Matrix

This matrix is the execution checklist for the Flutter AI Companion plans. `Existing` means the route already exists and its request shape must remain compatible. `New` means the backend foundation plan must add it before the client depends on it.

| Area | Method and route | Status | Required behavior |
| --- | --- | --- | --- |
| Auth | `POST /app/auth/code` | New | Send phone or email OTP for `login`, `register`, `reset`, or `bind`; return challenge ID, expiry and retry delay; never return the code. |
| Auth | `POST /app/auth/password-login` | New | Accept normalized phone/email plus password; return access and refresh tokens and user summary. |
| Auth | `POST /app/auth/code-login` | New | Consume a one-time login challenge and return the same token response. |
| Auth | `POST /app/auth/register` | New | Consume a register challenge, require password and create or resolve one unique user. |
| Auth | `POST /app/auth/reset-password` | New | Consume a reset challenge before changing password; revoke refresh tokens. |
| Auth | `POST /app/auth/refresh` | New | Rotate an opaque refresh token and issue a new access token. |
| Auth | `POST /app/auth/logout` | New | Revoke the current refresh token/session. |
| Auth | `GET /app/account` | New | Return user summary and verified contacts without password or token material. |
| Auth | `POST /app/account/contacts` | New | Bind the second phone/email after a `bind` challenge and enforce global uniqueness. |
| Auth | `PUT /app/account/password` | New | Change password after authenticated validation and revoke old refresh tokens. |
| Profiles | `GET /app/profiles` | New facade | Return selectable user-owned profiles, active version, memory flag, public voice and capability state. |
| Profiles | `GET /app/profiles/templates` | New facade | Return published preset metadata only. |
| Profiles | `POST /app/profiles` | New facade | Create from a template or safe custom defaults. |
| Profiles | `GET /app/profiles/{id}` | New facade | Return full editable public profile and bound-device summaries. |
| Profiles | `GET /app/profiles/{id}/model-options` | Existing-compatible | Return enabled model options without provider credentials. |
| Profiles | `GET /app/profiles/{id}/capability-options` | New facade | Return only published and authorized boolean capabilities. |
| Profiles | `PUT /app/profiles/{id}` | New facade | Validate the complete draft, including voice language/volume/rate/pitch and chat-history policy, then save, publish and activate atomically. |
| Profiles | `PUT /app/profiles/{id}/memory-settings` | New facade | Persist `enabled`; apply to new turns while an in-flight turn keeps its snapshot. |
| Profiles | `DELETE /app/profiles/{id}` | New facade | Reject bound profiles; otherwise hide profile and retain history/memory. |
| Profiles | `POST /app/profiles/{id}/avatar` | New facade | Store a validated image through the existing upload boundary and return public metadata only. |
| Devices | `GET /companion/devices` | Existing | List caller-owned devices without MQTT credentials. |
| Devices | `POST /companion/devices/bind` | Existing | Consume six-digit activation code and optional profile ID. |
| Devices | `GET /companion/devices/{id}` | Existing | Return online state, active profile and public capabilities. |
| Devices | `PUT /companion/devices/{id}` | Existing | Update alias and allowed basic settings. |
| Devices | `PUT /companion/devices/{id}/profile` | Existing-compatible | Change active profile; runtime refreshes the snapshot at the next device turn boundary, not during the current turn. |
| Devices | `POST /companion/devices/{id}/commands` | Existing | Allow only volume and brightness in the consumer App. |
| Devices | `DELETE /companion/devices/{id}` | Existing | Unbind the caller-owned device. |
| Memory | `GET /companion/profiles/{id}/memories` | New | Return `{enabled,items}`; `enabled` describes AI runtime use and existing items remain visible when false. |
| Memory | `PUT /companion/profiles/{id}/memories/{memoryId}` | New | User management may update one item regardless of runtime toggle. |
| Memory | `DELETE /companion/profiles/{id}/memories/{memoryId}` | New | User management may delete one item regardless of runtime toggle. |
| Memory | `DELETE /companion/profiles/{id}/memories` | New | User management may clear all items regardless of runtime toggle. |
| Memory | `/companion/devices/{id}/memories` | Existing-compatible | Keep hardware request shape; resolve device owner/profile and explicit source metadata to canonical user/profile namespace. |
| Conversations | `POST /api/v1/conversations` | Existing-compatible | Create a new runtime for the latest active profile version. |
| Conversations | `GET /api/v1/conversations` | New | Owner-scoped paged conversation index including App/device source. |
| Conversations | `GET /api/v1/conversations/{id}/history` | Existing-compatible | Owner-scoped text history with hidden-profile labels. |
| Conversations | `POST /api/v1/conversations/{id}/runtime` | New | Continue the same persistent conversation and its saved profile version, rebuilding durable turns into model context. |
| Conversations | `PATCH /api/v1/conversations/{id}` | New | Rename an owner-owned conversation. |
| Conversations | `DELETE /api/v1/conversations/{id}` | New | Hide/delete text history without touching profile memory. |
| Conversations | `POST /api/v1/conversations/{id}/turns/{turnId}/audio` | New | Regenerate a short-lived TTS result from saved text and profile version. |
| Internal | `POST /internal/public-conversations/{id}/history` | Existing-compatible | Server-secret append to Redis and durable turn store; idempotent by conversation/turn; new companion profiles do not persist raw audio. |

## WebSocket contract

The client connects to the `streamUrl` returned by manager-api using the `bearer.<runtimeToken>` subprotocol. Text and one-shot audio use `turn.text`, `turn.audio.start` plus binary data plus `turn.audio.end`, or the continuous stream contract. Full-screen calls use `web.session.start` with `pcm_s16le`, 16 kHz, one channel, then binary PCM frames and `input.audio.commit` after local VAD silence. `response.cancel` carries `turn_id` and `played_ms`; `stream.stop` ends continuous capture.

The server emits `session.ready`, `stream.ready`, `asr.partial`, `asr.final`, `turn.started`, `llm.delta`, `tts.audio` or `tts.audio.chunk`, `turn.interrupted`, `turn.completed`, `turn.cancelled`, `error`, `session.expiring`, and `session.expired`. Binary TTS is always paired with metadata carrying `transport:"binary"`, `audio_sequence`, `byte_length`, `mime_type`, and a complete decodable segment.

## Invariants

Profile version is fixed for a persistent App conversation. A device profile change applies at the next device turn. The canonical memory namespace is `companion:<userId>:<profileId>`. App and hardware memory routes map to that namespace and do not create device-specific copies. Conversation deletion does not delete memory. One App connection has one capture stream, one WebSocket, one active turn and one playback queue. The App never sends MQTT credentials or provider secrets.
