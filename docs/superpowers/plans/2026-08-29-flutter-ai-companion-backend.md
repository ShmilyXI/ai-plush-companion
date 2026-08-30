# Flutter AI Companion Backend Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the manager-api and xiaozhi-server contracts required by the consumer Flutter app without breaking existing management or hardware APIs.

**Architecture:** Keep the existing `/user/*`, `/companion/devices`, and hardware MQTT contracts compatible. Add an App-only authentication boundary, a durable owner/profile conversation index, a profile-scoped memory facade backed by one canonical user/profile namespace, and a runtime continuation endpoint. Python remains the audio runtime and writes completed turns through an idempotent internal Java endpoint.

**Tech Stack:** Spring Boot, MyBatis-Plus, Liquibase formatted SQL, Redis, JUnit 5, Python asyncio/aiohttp, pytest, existing `PublicConversationSession` and `CompanionMemoryService` abstractions.

---

## Scope and file map

The backend work is split into the following independently testable slices. Authentication can ship behind an App route before conversation persistence; conversation persistence can be tested with text-only turns before mobile audio; memory namespace migration can run behind a feature flag while the old device request shape remains available.

Authentication lives under `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/` and never changes the old graph-captcha controller. Durable conversations live under `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/` beside the existing public conversation code. Consumer profile and memory facades live under `server/main/manager-api/src/main/java/xiaozhi/modules/companion/`, while Python changes stay under `server/main/xiaozhi-server/core/public_conversation/` and its tests.

## Task 1: Add durable App authentication storage

**Files:**

- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291000.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291000-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/entity/AppContactEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/entity/AppAuthChallengeEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/entity/AppRefreshTokenEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dao/AppContactDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dao/AppAuthChallengeDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dao/AppRefreshTokenDao.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/appauth/AppAuthSchemaContractTest.java`

- [ ] **Step 1: Write the failing schema contract test.** Assert that the migration text contains unique `(channel, normalized_value)` and `(user_id, channel)` constraints and that the three tables contain expiry/revocation columns. Keep this test file-only so it runs without an external MySQL instance.

```java
@Test
void appAuthMigrationDefinesUniqueContactsAndOneTimeChallenges() throws IOException {
    String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608291000.sql"));
    assertTrue(sql.contains("UNIQUE KEY uk_app_contact_value (channel, normalized_value)"));
    assertTrue(sql.contains("UNIQUE KEY uk_app_contact_user_channel (user_id, channel)"));
    assertTrue(sql.contains("consumed_at DATETIME"));
    assertTrue(sql.contains("revoked_at DATETIME"));
}
```

- [ ] **Step 2: Run the contract test and verify it fails because the migration is absent.**

Run from `server/main/manager-api`:

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.appauth.AppAuthSchemaContractTest test
```

Expected: `FAIL` because `202608291000.sql` does not exist.

- [ ] **Step 3: Add the Liquibase migration.** Use the repository's formatted-SQL convention and `ASSIGN_ID`-compatible bigint IDs. Store only normalized contact values and hashes, never plaintext OTPs or refresh tokens.

```sql
-- liquibase formatted sql

-- changeset codex:202608291000
CREATE TABLE IF NOT EXISTS ai_app_contact (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    normalized_value VARCHAR(320) NOT NULL,
    verified_at DATETIME NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_contact_value (channel, normalized_value),
    UNIQUE KEY uk_app_contact_user_channel (user_id, channel),
    KEY idx_app_contact_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_app_auth_challenge (
    id VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    normalized_value VARCHAR(320) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_app_challenge_lookup (channel, normalized_value, purpose, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_app_refresh_token (
    id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    last_used_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_refresh_hash (token_hash),
    KEY idx_app_refresh_user (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 4: Register the new change set and rollback.** Add `202608291000` to the end of `db.changelog-master.yaml`; rollback drops only the three new tables in reverse dependency order.

- [ ] **Step 5: Implement the three MyBatis entities and DAO queries.** Add `findByNormalizedValue`, `findActiveChallenge`, `consumeChallenge`, `incrementFailedAttempts`, `findRefreshToken`, and `revokeRefreshToken` methods with parameterized SQL. Do not add a foreign key that would alter the repository's existing migration style.

- [ ] **Step 6: Run the schema test and Java compile.**

Run:

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.appauth.AppAuthSchemaContractTest test
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS` for both commands.

- [ ] **Step 7: Commit the storage slice.**

```bash
git add server/main/manager-api/src/main/resources/db/changelog/202608291000.sql \
  server/main/manager-api/src/main/resources/db/changelog/202608291000-rollback.sql \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-api/src/main/java/xiaozhi/modules/appauth \
  server/main/manager-api/src/test/java/xiaozhi/modules/appauth/AppAuthSchemaContractTest.java
git commit -m "feat: add consumer app auth storage"
```

## Task 2: Implement App OTP and password authentication

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/AppContactNormalizer.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/AppVerificationCodeService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/AppTokenService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/controller/AppAuthController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dto/AppSendCodeDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dto/AppPasswordLoginDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dto/AppCodeLoginDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dto/AppRegisterDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/dto/AppResetPasswordDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/vo/AppAuthTokenVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/service/AppAuthService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/service/impl/AppAuthServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/service/AppMessageSender.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/service/impl/SmsAppMessageSender.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/appauth/service/impl/EmailAppMessageSender.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/oauth2/Oauth2Filter.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/appauth/AppContactNormalizerTest.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/appauth/AppAuthServiceTest.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/appauth/AppAuthControllerTest.java`

- [ ] **Step 1: Specify the endpoint payloads in controller tests.** Cover `POST /app/auth/code`, `/password-login`, `/code-login`, `/register`, `/reset-password`, `/refresh`, and `/logout`. A code request returns `{challengeId, expiresAt, retryAfterSeconds}`; token responses return `{accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, user}`.

- [ ] **Step 2: Run the new controller tests to establish red tests.**

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.appauth.AppAuthControllerTest,xiaozhi.modules.appauth.AppAuthServiceTest test
```

Expected: `FAIL` because the App auth controller and service do not exist.

- [ ] **Step 3: Implement contact normalization.** Normalize phone input to E.164 using the submitted country code, normalize email with trim plus lowercase, reject malformed values, and use the normalized value for all uniqueness and challenge lookups.

```java
public record NormalizedContact(String channel, String value) {}

public NormalizedContact normalize(String channel, String raw, String countryCode) {
    if ("email".equals(channel)) return new NormalizedContact("email", validateEmail(raw));
    if ("phone".equals(channel)) return new NormalizedContact("phone", toE164(raw, countryCode));
    throw new IllegalArgumentException("unsupported contact channel");
}
```

- [ ] **Step 4: Implement challenge issuance and verification.** Generate a six-digit code with `SecureRandom`, hash it with an HMAC key held in server configuration, expire after 10 minutes, allow five failed attempts, enforce a 60-second per-contact send interval and a daily per-contact limit, and atomically mark a successful challenge consumed. Call `SmsService.sendVerificationCodeSms` for phone delivery and a new configured `JavaMailSender` adapter for email delivery. Log only channel, purpose, and delivery result.

- [ ] **Step 5: Implement registration and login.** Registration must consume a challenge for `register`, create or resolve one `sys_user`, insert the verified contact, and hash the submitted password with `PasswordUtils`. Password login accepts either contact channel; code login consumes a `login` challenge. A verified contact already owned by another user returns a conflict and never creates a second account.

- [ ] **Step 6: Implement refresh-token rotation.** Issue a random opaque refresh token, store only its SHA-256/HMAC hash, rotate it on every refresh, revoke the old row, and issue a short-lived access token through the existing Shiro-compatible token mechanism. Add anonymous routes for `/app/auth/**` and make authenticated App routes resolve the same `SecurityUser` identity.

- [ ] **Step 7: Implement reset and contact binding.** `reset-password` consumes a `reset` challenge before changing the password and revokes all refresh tokens for the user. Binding a second contact consumes a `bind` challenge and enforces the same global uniqueness constraint. Existing `/user/*` behavior remains unchanged.

- [ ] **Step 8: Run focused authentication tests and the existing security suite.**

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.appauth.*Test,xiaozhi.modules.security.config.ShiroConfigTest,xiaozhi.modules.security.oauth2.Oauth2FilterWebSessionTest test
```

Expected: all focused tests pass; old graph-captcha tests remain green.

- [ ] **Step 9: Commit the authentication slice.**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/appauth \
  server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/security/oauth2/Oauth2Filter.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/appauth
git commit -m "feat: add consumer app authentication"
```

## Task 3: Add consumer profile, capability catalog, and atomic save

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/AppProfileController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/AppProfileFacade.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/AppProfileFacadeImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/dto/AppProfileCreateDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/dto/AppProfileSaveDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/vo/AppProfileVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/AppProfileAvatarController.java`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291015.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291015-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionProfileServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentChatHistoryBizServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionSubscriptionServiceImpl.java`
- Modify: `server/main/manager-api/pom.xml`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/AppProfileFacadeTest.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/controller/AppProfileControllerTest.java`

- [ ] **Step 1: Add profile lifecycle columns and write red tests.** Add `memory_enabled TINYINT(1) NOT NULL DEFAULT 1` and `consumer_deleted_at DATETIME NULL` to `ai_agent` through a new idempotent migration. Test that a save calls the existing snapshot service, publish service, and activate service exactly once.

- [ ] **Step 2: Add consumer creation paths.** `POST /app/profiles` accepts `{source:"template",templateId,name}` or `{source:"custom",name}`. Template creation delegates to the existing `createFromTemplate`; custom creation uses the default companion template only to populate safe model and prompt defaults, stores the profile as a draft with `activeVersionNo=0`, and returns a draft marker until the first explicit save. New companion profiles set chat-history policy to text-only so raw audio is not persisted.

- [ ] **Step 3: Add strict public catalog methods.** Return only user-owned profiles with `consumer_deleted_at IS NULL`, published model options, voices matching the selected TTS model, and capability entries that are published and entitled. Include every consumer-editable field required by the current Agent model, including TTS language/volume/rate/pitch, chat-history policy and avatar metadata. Strip provider URLs, secrets, MCP credentials, and raw prompt data from list responses.

- [ ] **Step 3a: Add avatar upload metadata.** Reuse the existing upload storage boundary through `AppProfileAvatarController`, return a public asset URL plus checksum, reject executable or oversized files, and keep the previous avatar URL until the replacement is committed. Add `spring-boot-starter-mail` and SMTP settings for the App email-code sender in the same dependency slice.

- [ ] **Step 4: Implement atomic profile save.** Validate every submitted field, load the profile with a row lock, validate model/voice compatibility and capability publication, write all fields, create a snapshot, publish it, activate it, and commit as one transaction. If publish or activation fails, roll back the profile update and leave the previous active version untouched.

- [ ] **Step 5: Implement role-level memory toggle.** Add `PUT /app/profiles/{id}/memory-settings` with `{enabled}`. Save the value as part of the profile version and make it effective at the next conversation turn; an in-flight runtime bundle keeps the previous value. Ensure the default App entitlement enables long-term memory and has configurable device/profile limits high enough for the two-device acceptance fixture.

- [ ] **Step 6: Implement soft delete protection.** Reject deletion while `getDeviceCountByAgentId(id) > 0`; otherwise set `consumer_deleted_at` and exclude the profile from selection while retaining snapshots, conversations, and memory. Return a stable `profile_deleted` error for later writes.

- [ ] **Step 6a: Make the App default entitlement explicit.** Add a named, configurable default entitlement for App users with long-term memory enabled and device/profile limits sufficient for the acceptance fixture (at least two devices and five profiles). Keep the plan hidden from the App until subscription work is started.

- [ ] **Step 7: Run profile tests and existing companion tests.**

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.companion.service.AppProfileFacadeTest,xiaozhi.modules.companion.controller.AppProfileControllerTest,xiaozhi.modules.companion.service.CompanionDeviceBindingConcurrencyTest test
```

Expected: new tests and existing profile/device tests pass.

- [ ] **Step 8: Commit the profile slice.**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion \
  server/main/manager-api/src/main/resources/db/changelog/202608291015* \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion
git commit -m "feat: add consumer profile facade"
```

## Task 4: Persist conversations and expose owner-scoped history

**Files:**

- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291030.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608291030-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/entity/CompanionConversationEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/entity/CompanionConversationTurnEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/dao/CompanionConversationDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/dao/CompanionConversationTurnDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/CompanionConversationIndexService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/CompanionConversationIndexServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationHistoryController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/InternalPublicConversationController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/RedisPublicConversationHistoryStore.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/CompanionConversationIndexServiceTest.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationControllerTest.java`

- [ ] **Step 1: Write red service tests for list, rename, delete, continuation, and idempotent turn append.** Use owner IDs 10 and 11 and assert user 11 cannot read or mutate user 10's conversation.

- [ ] **Step 2: Add durable conversation tables.** Create `ai_companion_conversation` with `id`, `owner_id`, `profile_id`, `profile_version_no`, `source`, `title`, `last_activity_at`, `deleted_at`, `created_at`, and `updated_at`; create `ai_companion_conversation_turn` with `conversation_id`, `turn_id`, `request_id`, `source`, `user_text`, `assistant_text`, `occurred_at`, and a unique `(conversation_id, turn_id)` key. Do not add audio blobs.

- [ ] **Step 3: Implement the index service.** Add methods `create`, `list(ownerId,cursor,limit)`, `rename`, `softDelete`, `requireReadable`, `appendTurnIfAbsent`, and `history`. Titles default to the first non-empty user text truncated to 80 Unicode code points; later automatic title generation is out of scope. Sort by `last_activity_at DESC, id DESC`.

- [ ] **Step 4: Extend public conversation creation and continuation.** Accept an optional `conversationId` only through a new `POST /api/v1/conversations/{id}/runtime` route. Verify owner and stored profile version, issue a fresh 15-minute runtime token for that same conversation, rebuild the persisted turns into the model dialogue context, and never silently switch its profile.

- [ ] **Step 5: Add public history CRUD.** Add `GET /api/v1/conversations`, `PATCH /api/v1/conversations/{id}`, `DELETE /api/v1/conversations/{id}`, and `POST /api/v1/conversations/{id}/runtime`; keep existing `POST /api/v1/conversations` for new sessions. All routes use the existing bearer/API-key auth boundary and owner checks.

- [ ] **Step 6: Add internal durable append and device source support.** Extend `POST /internal/public-conversations/{id}/history` to write both the existing short-lived Redis list and the durable turn row. Require a server-secret header for internal calls, accept `source:"app"|"device"`, and make duplicate turn IDs return success without a second row. New companion/device profiles must disable raw audio persistence while legacy audio rows remain inaccessible to the App.

- [ ] **Step 7: Add historical audio regeneration.** Implement `POST /api/v1/conversations/{id}/turns/{turnId}/audio`, verify ownership, load the stored profile version and assistant text, call the existing TTS service, and return a short-lived stream or signed URL. Never persist the resulting bytes.

- [ ] **Step 8: Run conversation tests.**

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.conversation.CompanionConversationIndexServiceTest,xiaozhi.modules.conversation.PublicConversationControllerTest,xiaozhi.modules.conversation.PublicConversationServiceTest test
```

Expected: owner isolation, continuation, CRUD, title derivation, duplicate append, and legacy public history tests pass.

- [ ] **Step 9: Commit the conversation slice.**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/conversation \
  server/main/manager-api/src/main/resources/db/changelog/202608291030* \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-api/src/test/java/xiaozhi/modules/conversation
git commit -m "feat: persist consumer conversations"
```

## Task 5: Move memory to a canonical profile namespace while preserving hardware routes

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/memory/AppProfileMemoryController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/memory/ProfileMemoryService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/memory/ProfileMemoryNamespace.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/memory/MemoryMigrationCoordinator.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionMemoryController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/CompanionMemoryServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionProfileServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationServiceImpl.java`
- Modify: `server/main/xiaozhi-server/core/public_conversation/session.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/service.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/handle/reportHandle.py`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/memory/ProfileMemoryServiceTest.java`
- Tests: `server/main/manager-api/src/test/java/xiaozhi/modules/device/service/impl/CompanionMemoryCompatibilityTest.java`
- Tests: `server/main/xiaozhi-server/tests/test_public_conversation_memory_namespace.py`

- [ ] **Step 1: Write red namespace and toggle tests.** Assert that user 7/profile `p1` maps to `companion:7:p1`, user 7/profile `p2` maps elsewhere, and a disabled profile never calls the memory provider for read or write.

- [ ] **Step 2: Implement the canonical namespace resolver.** Resolve `companion:<userId>:<profileId>` from a locked, owner-checked profile. Include source metadata with device ID or conversation ID, but never append those IDs to the namespace.

- [ ] **Step 3: Add App profile memory routes.** Implement `GET /companion/profiles/{profileId}/memories`, `PUT /companion/profiles/{profileId}/memories/{memoryId}`, `DELETE /companion/profiles/{profileId}/memories/{memoryId}`, and `DELETE /companion/profiles/{profileId}/memories` for clear. The list response is `{enabled,items}` where `enabled` describes AI runtime use; it still returns existing items when false. Runtime recall and automatic writes are skipped when disabled, while authenticated user management updates, deletes and clear operations remain allowed.

- [ ] **Step 4: Adapt hardware memory requests.** Keep `/companion/devices/{deviceId}/memories` request and response shapes. Resolve device owner and active profile, then delegate to `ProfileMemoryService` with the canonical namespace and explicit user/profile/source metadata. Retain migration endpoints only for legacy data recovery.

- [ ] **Step 5: Change public conversation bundles.** Replace per-conversation `memoryNamespace` with the canonical profile namespace and include `memoryEnabled` in the bundle. Keep conversation ID in source metadata for session isolation and audit.

- [ ] **Step 6: Migrate legacy namespaces idempotently.** On first canonical access, merge device and old public conversation namespaces once, using a deterministic content/source hash to deduplicate. Record migration status and source counts in an audit row or existing migration table; retry failures without deleting the source until canonical write succeeds.

- [ ] **Step 7: Run Java and Python memory tests.**

```bash
mvn -DskipTests=false -Dtest=xiaozhi.modules.companion.memory.ProfileMemoryServiceTest,xiaozhi.modules.device.service.impl.CompanionMemoryCompatibilityTest,xiaozhi.modules.companion.service.CompanionDeviceBindingConcurrencyTest test
cd ../xiaozhi-server
python -m pytest tests/test_public_conversation_memory_namespace.py tests/test_companion_memory_management.py tests/test_external_memory_clear.py -q
```

Expected: canonical sharing, disabled behavior, hardware compatibility, and idempotent migration tests pass.

- [ ] **Step 8: Commit the memory slice.**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion \
  server/main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/CompanionMemoryServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationServiceImpl.java \
  server/main/xiaozhi-server/core/public_conversation \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion \
  server/main/manager-api/src/test/java/xiaozhi/modules/device \
  server/main/xiaozhi-server/tests/test_public_conversation_memory_namespace.py
git commit -m "feat: share companion memory by profile"
```

## Task 6: Make Python runtime archive App and device turns consistently

**Files:**

- Modify: `server/main/xiaozhi-server/core/public_conversation/session.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/service.py`
- Modify: `server/main/xiaozhi-server/core/api/public_conversation_handler.py`
- Modify: `server/main/xiaozhi-server/core/public_conversation/protocol.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/handle/reportHandle.py`
- Modify: `server/main/xiaozhi-server/core/handle/sendAudioHandle.py`
- Modify: `server/main/xiaozhi-server/tests/test_public_conversation_http.py`
- Modify: `server/main/xiaozhi-server/tests/test_public_conversation_session.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_durable_history.py`

- [ ] **Step 1: Add a durable-history callback contract.** Extend the runtime client with `append_history(conversation_id, item)` carrying `source`, `turn_id`, `request_id`, `text`, `reply`, and `occurred_at`; preserve the existing Redis fallback when Java is unavailable.

- [ ] **Step 2: Add runtime continuation validation.** Validate that a runtime token's conversation ID, owner, agent ID, and stored profile version agree before opening a WebSocket. Reject a token for a deleted conversation with a non-retryable error.

- [ ] **Step 3: Preserve continuous-stream behavior.** Keep `web.session.start`, binary PCM, `input.audio.commit`, `response.cancel`, `stream.stop`, sequence numbers, and TTS binary pairing unchanged for existing clients. In `connection.py` and `reportHandle.py`, add a device-turn boundary refresh that reloads the active profile snapshot only after the previous turn is terminal; the current turn keeps its old prompt, model, voice and memory flag. In `sendAudioHandle.py` and the device history path, prevent new companion turns from writing raw audio while leaving legacy audio rows readable only to the management console. Add tests that a reconnect uses the same conversation ID but does not duplicate a completed turn.

- [ ] **Step 4: Run Python public-conversation tests.**

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_public_conversation_http.py tests/test_public_conversation_session.py tests/test_public_conversation_durable_history.py tests/test_public_conversation_streaming_http.py -q
```

Expected: all selected tests pass and existing JSON/base64 compatibility remains green.

- [ ] **Step 5: Commit the runtime slice.**

```bash
git add server/main/xiaozhi-server/core/public_conversation server/main/xiaozhi-server/core/api/public_conversation_handler.py server/main/xiaozhi-server/tests/test_public_conversation_*.py
git commit -m "feat: archive public conversation turns"
```

## Task 7: Publish the contract and migration evidence

**Files:**

- Modify: `docs/public-conversation-api.yaml`
- Create: `docs/app-auth-api.yaml`
- Create: `docs/consumer-app-backend-migration.md`
- Modify: `docs/project-requirements-audit.md`

- [ ] **Step 1: Document every App auth, profile, conversation, memory, and historical-audio route.** Include request schemas, error codes (`memory_disabled`, `profile_deleted`, `conversation_not_found`, `contact_conflict`), owner rules, and token lifetimes.

- [ ] **Step 2: Document namespace migration and rollback.** Record how legacy device/public namespaces are detected, merged, audited, retried, and left intact on failure. State that hardware request paths remain compatible.

- [ ] **Step 3: Run OpenAPI syntax and repository checks.**

```bash
python - <<'PY'
import yaml
for path in ("docs/public-conversation-api.yaml", "docs/app-auth-api.yaml"):
    with open(path, encoding="utf-8") as handle:
        document = yaml.safe_load(handle)
    assert document["openapi"].startswith("3.")
    assert document["paths"]
print("openapi-ok")
PY
git diff --check
```

Expected output: `openapi-ok` and no diff-check errors.

- [ ] **Step 4: Commit contract documentation.**

```bash
git add docs/public-conversation-api.yaml docs/app-auth-api.yaml docs/consumer-app-backend-migration.md docs/project-requirements-audit.md
git commit -m "docs: publish consumer app backend contract"
```

## Backend gate before Flutter work

The Flutter client work may start after Tasks 1 through 4 pass in a local environment. Tasks 5 and 6 must pass before claiming cross-device memory or device-transcript parity. Run the manager-api focused suite, the public-conversation pytest subset, and `git diff --check` at the gate. Do not mark the App backend complete until a real device turn and an App turn both appear under the same user/profile conversation and memory namespace.
