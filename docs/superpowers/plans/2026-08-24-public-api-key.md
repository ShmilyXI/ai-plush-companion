# Public Conversation API Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add secure, revocable, scoped API Keys for third-party server callers without exposing user tokens, provider credentials, or unrestricted Agent tools.

**Architecture:** Java owns API Key creation, hashing, scope checks, Agent allowlists, expiration, revocation, last-use timestamps, and audit records. The public conversation auth resolver accepts either an existing user token or an API Key and produces one ownership context for the existing session service. Python continues to trust only the short-lived runtime token; raw API Keys never leave Java.

**Tech Stack:** Spring Boot, Shiro, MyBatis-Plus, Liquibase SQL changelog, HMAC/SHA-256, JUnit 5, Mockito.

---

### Task 1: Add the API Key persistence contract

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608240100.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608240100-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/entity/PublicConversationApiKeyEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/dao/PublicConversationApiKeyDao.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationApiKeySchemaTest.java`

- [x] **Step 1: Write schema and mapping tests**

Assert that the table has a generated primary key, owner ID, display name, non-reversible `key_hash`, non-secret `key_prefix`, JSON scopes, JSON Agent allowlist, expiry, revoked flag, last-used timestamp, created timestamp, and updated timestamp. Assert that the entity has no field containing the original API Key.

- [x] **Step 2: Add Liquibase forward and rollback SQL**

Create `ai_public_conversation_api_key` with unique `(user_id, key_hash)`, indexes for owner/revoked/expiry and hash lookup, `utf8mb4`, and no plaintext secret column. The rollback must drop only this table. Add a new changeSet to the master changelog; never edit an earlier changeSet.

- [x] **Step 3: Add MyBatis entity and owned lookup methods**

The DAO must support `selectByHash`, `selectOwnedByUser`, `insert`, `updateById`, and `selectOwnedForUpdate`. Scope and Agent allowlist JSON must use Jackson handlers already used by the repository.

- [x] **Step 4: Run schema/mapping checks and commit**

```bash
cd server/main/manager-api
JAVA_HOME=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home PATH=$JAVA_HOME/bin:$PATH mvn -q -Dtest=PublicConversationApiKeySchemaTest test
git add src/main/resources/db/changelog/202608240100* src/main/resources/db/changelog/db.changelog-master.yaml src/main/java/xiaozhi/modules/conversation/entity src/main/java/xiaozhi/modules/conversation/dao src/test/java/xiaozhi/modules/conversation/PublicConversationApiKeySchemaTest.java
git commit -m "feat: persist public conversation api key metadata"
```

### Task 2: Implement creation, listing, revocation, and secret hashing

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/PublicConversationApiKeyService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationApiKeyServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/dto/PublicConversationApiKeyCreateDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/vo/PublicConversationApiKeyVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationApiKeyController.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationApiKeyServiceTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationApiKeyControllerTest.java`

- [x] **Step 1: Write red service tests**

Cover one-time secret return, SHA-256 hash mismatch with the original value, default scopes, Agent allowlist validation, expiry validation, owner isolation, revoke idempotency, and refusal to return the secret from list/get responses.

- [x] **Step 2: Generate and hash keys**

Generate 32 random bytes, encode as URL-safe `pc_` plus base64 without padding, store only SHA-256 hex and an eight-character prefix. Store scopes as a fixed enum set: `conversation:text`, `conversation:audio`, `conversation:override`, `resource:read`, and `device:control`. Reject unknown scopes and Agent IDs the owner cannot access.

- [x] **Step 3: Add owner-scoped management endpoints**

Expose `POST /api/v1/api-keys`, `GET /api/v1/api-keys`, and `DELETE /api/v1/api-keys/{id}` for the current user. Creation returns the plaintext key once in a dedicated `createdSecret` field; all later responses return only prefix, scopes, Agent allowlist, expiry, revoked, last-used, and timestamps.

- [x] **Step 4: Run service/controller tests and commit**

```bash
cd server/main/manager-api
JAVA_HOME=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home PATH=$JAVA_HOME/bin:$PATH mvn -q -Dtest=PublicConversationApiKeyServiceTest,PublicConversationApiKeyControllerTest test
git commit -am "feat: manage scoped public conversation api keys"
```

### Task 3: Resolve API Keys for public conversations

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/PublicConversationAuthService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationAuthServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/controller/PublicConversationController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationAuthServiceTest.java`

- [x] **Step 1: Write auth resolution tests**

Assert that a valid `ApiKey <secret>` resolves owner and scopes, revoked/expired/wrong keys fail, an API Key with an Agent allowlist cannot create another Agent session, and a user token continues to use the existing `SecurityUser` path. Assert that every successful use updates `last_used_at` without logging the key.

- [x] **Step 2: Add a dedicated public auth filter**

Route only `/api/v1/conversations/**` and `/api/v1/api-keys/**` through a public auth filter that accepts the existing user Bearer token or `ApiKey` scheme. Do not loosen `/internal/**`, device, admin, or legacy routes. Put the resolved principal and scopes in a request-scoped context, never in a static field.

- [x] **Step 3: Enforce scopes in session creation and resources**

Require `conversation:text` or `conversation:audio` for matching input modes, `conversation:override` for voice/model overrides, `resource:read` for list endpoints, and `device:control` for device commands. Apply Agent allowlists before calling `AgentService` and keep the existing user ownership checks.

- [ ] **Step 4: Run auth tests and commit**

```bash
cd server/main/manager-api
JAVA_HOME=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home PATH=$JAVA_HOME/bin:$PATH mvn -q -Dtest=PublicConversationAuthServiceTest,PublicConversationServiceTest,PublicConversationResourceControllerTest test
git commit -am "feat: authorize public conversations with scoped api keys"
```

### Task 4: Audit, rate-limit, and document the API Key surface

**Files:**
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationApiKeyServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/conversation/service/impl/PublicConversationAuthServiceImpl.java`
- Modify: `scripts/generate-runtime-openapi.mjs`
- Modify: `docs/public-conversation-progress.md`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/conversation/PublicConversationApiKeyRedactionTest.java`

- [x] **Step 1: Add rate and expiry guards**

Limit active keys per user, reject keys past expiry, reject more than the configured failed-auth attempts per source window, and use the existing audit service for create/revoke/use events. Log only key ID and prefix.

- [x] **Step 2: Add OpenAPI schemas and examples**

Document the `ApiKey` scheme, creation response one-time secret rule, scopes, resource allowlist, revocation, and all public conversation endpoints. Add a redaction test that rejects `api_key`, `access_token`, `secret`, `password`, and `createdSecret` from non-creation response examples.

- [x] **Step 3: Run complete API Key verification**

```bash
cd server/main/manager-api
JAVA_HOME=/Users/xiaox/WorkShop/ai-plush-companion-public/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home PATH=$JAVA_HOME/bin:$PATH mvn -q -Dtest='xiaozhi.modules.conversation.**' test
cd /Users/xiaox/WorkShop/ai-plush-companion-public
git diff --check
```

- [ ] **Step 4: Update progress and commit**

Only mark third-party API access ready after persistence, revoke, scope tests, OpenAPI redaction, and a non-audio external client contract all pass. Keep hardware audio tests untouched and do not run sound-producing checks at night.
