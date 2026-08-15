# Device Capability and Skill Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a backend-managed capability center where administrators create versioned Skills from existing Plugin, MCP, and device tools, then bind and override them per device while preserving the current manual volume and brightness controls.

**Architecture:** `manager-api` owns capability definitions, immutable published versions, secrets, device bindings, effective bundles, and audit records. `xiaozhi-server` caches the effective bundle per device, routes utterances through deterministic rules plus an LLM classifier, and exposes only the selected Skill tools through the existing unified tool manager. `companion-console` manages capabilities and device bindings; firmware remains the source of truth for hardware tools.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Liquibase, JUnit 5, Python 3.10, asyncio, aiohttp/httpx, pytest, React 19, TypeScript, Ant Design, Vitest.

---

### Task 1: Add the capability schema and mapped entities

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608161100.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608161100-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/CapabilityEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/CapabilityVersionEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/SkillDefinitionEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/SkillTriggerEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/SkillToolMappingEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/DeviceSkillMappingEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/PluginDefinitionEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/McpServerEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/McpToolSnapshotEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/DeviceToolSnapshotEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/CapabilitySecretEntity.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilitySchemaContractTest.java`

- [ ] **Step 1: Write the schema contract test**

Create a JUnit test that reads the migration and rollback resources, parses the Liquibase master file, and asserts the eleven table names, the unique keys for capability versions and device bindings, encrypted secret storage, device foreign keys, and reverse-order rollback. Also assert every entity has the expected `@TableName` value.

- [ ] **Step 2: Run the contract test and verify failure**

Run `cd server/main/manager-api && mvn -DskipTests=false -Dtest=CapabilitySchemaContractTest test`.

Expected result: compilation or resource lookup fails because the migration and entity classes do not exist.

- [ ] **Step 3: Add the forward and rollback migrations**

Use `varchar(32)` capability and device identifiers compatible with existing IDs. Store prompts and immutable version content in `longtext`, hashes in `char(64)`, structured non-secret fields in MySQL `json`, and secret values only in `longtext` ciphertext columns. Add foreign keys with explicit names and indexes for type/status, skill triggers, skill tools, device bindings, MCP snapshots, and device tool snapshots.

- [ ] **Step 4: Add focused MyBatis entities**

Each entity must contain only its table fields. Use `@TableId(type = IdType.ASSIGN_UUID)` for string primary keys, `@TableId(type = IdType.ASSIGN_ID)` for numeric mapping rows, `Date` for timestamps, and plain JSON strings for schema payloads so API validation remains in the service layer.

- [ ] **Step 5: Register the change set and run the test**

Append a change set with forward and rollback SQL files without editing existing change sets. Run the contract test again and expect `BUILD SUCCESS`.

- [ ] **Step 6: Commit the schema**

Commit only the new migration, master-file hunk, entities, and schema test with `feat: add capability center schema`.

### Task 2: Implement capability DTOs, validation, persistence, and immutable publishing

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/CapabilityDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/CapabilityVersionDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/SkillDefinitionDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/SkillTriggerDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/SkillToolMappingDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/DeviceSkillMappingDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/PluginDefinitionDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/McpServerDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/McpToolSnapshotDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/DeviceToolSnapshotDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/CapabilitySecretDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/CapabilitySaveDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/SkillTriggerDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/SkillToolDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/PluginDefinitionDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/McpServerDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityServiceImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityServiceImplTest.java`

- [ ] **Step 1: Write service tests first**

Cover creating a Skill draft, rejecting unsupported capability types, rejecting Skill code fields, rejecting missing tool references, rejecting malformed regex, updating a draft, publishing version one, preserving published content after later draft edits, publishing version two, disabling a capability, and refusing to delete referenced tools.

- [ ] **Step 2: Run tests and verify failure**

Run `cd server/main/manager-api && mvn -DskipTests=false -Dtest=CapabilityServiceImplTest test`.

Expected result: the service and DTO classes are missing.

- [ ] **Step 3: Implement strict DTO validation**

`CapabilitySaveDTO` accepts `type`, `name`, `description`, `executionPrompt`, `semanticThreshold`, `responseMode`, `timeoutMs`, `failureMessage`, `triggers`, `tools`, `plugin`, and `mcp`. Reject any unknown executable field such as `code`, `script`, `command` on Skill payloads. Restrict response modes to `LLM` and `FIXED`, timeout to 1000 through 120000 milliseconds, and semantic threshold to 0 through 1.

- [ ] **Step 4: Implement transactional aggregate persistence**

Create or update the public capability row and replace only the type-specific draft rows inside one transaction. Tool mappings reference stable capability IDs or snapshot IDs and are validated before persistence. Use `CompanionAuditService.record` for create, update, publish, disable, and delete actions.

- [ ] **Step 5: Implement immutable publishing**

Serialize a canonical aggregate map with ordered keys, compute SHA-256, insert a new `ai_capability_version` row, update `published_version`, and never update an existing version row. Return the published aggregate in `CapabilityVO` without secret ciphertext.

- [ ] **Step 6: Run tests and commit**

Run the service tests, then commit with `feat: manage versioned capabilities`.

### Task 3: Expose administrator capability APIs and encrypted secret handling

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/controller/AdminCapabilityController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/CapabilitySecretSaveDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilitySecretService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilitySecretServiceImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/AdminCapabilityControllerTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilitySecretServiceImplTest.java`

- [ ] **Step 1: Write controller and secret tests**

Test list, get, create, update, publish, disable, delete, secret save, and secret status endpoints. Prove all endpoints require `sys:role:superAdmin`, responses never contain ciphertext or plaintext secrets, and blank replacement values preserve existing configured secrets.

Also test `POST /admin/companion/capabilities/route-preview` with a device ID and utterance. The response must show deterministic matches, whether semantic classification is required, the eligible Skill IDs, and the effective allowed tools without executing any tool.

- [ ] **Step 2: Run the tests and verify failure**

Run both test classes through Maven and expect missing controller/service failures.

- [ ] **Step 3: Implement encrypted secrets**

Reuse `CompanionModelSecretService` for AES-backed encryption. Store one named secret record per capability. Return only `configured: true|false` and never decrypt through public APIs.

- [ ] **Step 4: Implement `/admin/companion/capabilities` APIs**

Use paged list parameters `type`, `status`, `keyword`, `page`, and `limit`. Add `POST /{id}/publish`, `PUT /{id}/status`, and nested secret endpoints. Keep the existing `AdminCompanionController` unchanged by placing this feature in a dedicated controller.

The route-preview endpoint uses the same effective device bindings and rule scoring contract as the Python router. It returns `selectedSkillId` only for an unambiguous deterministic match; semantic cases return ordered candidates and `semanticRequired=true`.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: expose capability administration api`.

### Task 4: Implement per-device Skill binding and effective bundle calculation

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/DeviceSkillBindingDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/DeviceSkillBindingVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/EffectiveCapabilityBundleVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/DeviceCapabilityService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/DeviceCapabilityServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionDeviceController.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/DeviceCapabilityServiceImplTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CompanionDeviceCapabilityControllerTest.java`

- [ ] **Step 1: Write ownership and merge tests**

Cover owner access, cross-user denial, super-admin administration, latest-version binding, fixed-version binding, device override precedence, rejection of non-overridable fields, role switching without binding changes, and monotonically increasing device configuration versions.

- [ ] **Step 2: Verify test failure**

Run the two new test classes and expect missing service/controller behavior.

- [ ] **Step 3: Implement binding APIs**

Add `GET /companion/devices/{id}/skills` and `PUT /companion/devices/{id}/skills`. Each save replaces the owned device binding set transactionally after locking the device. Only published Skill capabilities can be bound.

- [ ] **Step 4: Implement effective bundle calculation**

Resolve each enabled mapping to either its fixed immutable version or the current published version. Merge tool defaults, Skill defaults, and allowed device overrides in that order. Emit a stable bundle with `configVersion`, `skills`, `tools`, and no secret values.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: bind skills to devices`.

### Task 5: Add internal runtime bundle, secret, and device-tool snapshot endpoints

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/controller/InternalCapabilityController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/DeviceToolSnapshotSaveDTO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/security/config/ShiroConfigTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/InternalCapabilityControllerTest.java`

- [ ] **Step 1: Write internal endpoint security tests**

Test `GET /internal/capabilities/devices/{deviceId}/bundle`, `POST /internal/capabilities/devices/{deviceId}/tools`, and `GET /internal/capabilities/secrets/{secretId}`. Prove OAuth tokens alone cannot use them and the server-secret filter protects the route.

- [ ] **Step 2: Verify failure**

Run the new tests and the existing Shiro test.

- [ ] **Step 3: Implement internal endpoints**

Bundle lookup identifies the device directly and does not accept caller-supplied user IDs. Tool snapshot ingestion upserts tool name, schema, firmware version, availability, and last-seen time. Secret lookup returns a value only when the requested secret is referenced by an enabled capability in the requesting device bundle.

- [ ] **Step 4: Register the server filter**

Add `/internal/capabilities/**` before the catch-all OAuth mapping without disturbing existing user edits in `ShiroConfig.java`.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: serve runtime capability bundles`.

### Task 6: Seed Plugin definitions and official weather, news, and search Skills

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapService.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityBootstrapServiceTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/init/CompanionBootstrapService.java`

- [ ] **Step 1: Write idempotent bootstrap tests**

Test that repeated bootstrap creates one Plugin definition and one published Skill for each of `get_weather`, `get_news_from_newsnow`, and `web_search`. Assert the official Skills contain Chinese trigger keywords, positive examples, negative examples, execution prompts, and exactly one allowed Plugin tool.

- [ ] **Step 2: Verify failure**

Run the bootstrap test and expect the service to be missing.

- [ ] **Step 3: Implement official capabilities**

Use stable IDs `plugin-weather`, `plugin-news`, `plugin-web-search`, `skill-weather`, `skill-news`, and `skill-web-search`. Store no real API keys in bootstrap content. Plugin config schemas mark secret fields, and existing stored plugin values are migrated into encrypted capability secrets or non-secret defaults.

- [ ] **Step 4: Implement legacy binding migration**

For every `ai_agent_plugin_mapping`, find all current `ai_device` rows using that agent. Create equivalent device Skill bindings and override JSON. Record unmapped agent rows in the audit log and leave them untouched for later retry.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: seed official device skills`.

### Task 7: Add the Python capability client and five-minute per-device cache

**Files:**
- Create: `server/main/xiaozhi-server/core/capabilities/__init__.py`
- Create: `server/main/xiaozhi-server/core/capabilities/models.py`
- Create: `server/main/xiaozhi-server/core/capabilities/client.py`
- Create: `server/main/xiaozhi-server/core/capabilities/cache.py`
- Modify: `server/main/xiaozhi-server/config/manage_api_client.py`
- Create: `server/main/xiaozhi-server/tests/test_capability_bundle_client.py`
- Create: `server/main/xiaozhi-server/tests/test_capability_bundle_cache.py`

- [ ] **Step 1: Write client and cache tests**

Cover strict bundle parsing, secret redaction in errors, one request for concurrent cache misses, config-version replacement, five-minute stale fallback, expired-cache fail closed behavior, and explicit invalidation.

- [ ] **Step 2: Run pytest and verify failure**

Run `cd server/main/xiaozhi-server && pytest -q tests/test_capability_bundle_client.py tests/test_capability_bundle_cache.py`.

- [ ] **Step 3: Implement immutable Python models**

Use frozen dataclasses for triggers, tool references, Skills, and bundles. Reject unknown capability types, missing published versions, malformed regex configuration, and tool references absent from the bundle tool map.

- [ ] **Step 4: Implement authenticated internal requests and cache**

Reuse the manager API base URL and `secret` header convention. Never log response bodies on parse or authentication failures. Use `time.monotonic()` and an `asyncio.Lock` per device.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: load device capability bundles`.

### Task 8: Implement mixed Skill routing

**Files:**
- Create: `server/main/xiaozhi-server/core/capabilities/router.py`
- Create: `server/main/xiaozhi-server/core/capabilities/classifier.py`
- Create: `server/main/xiaozhi-server/tests/test_skill_router.py`
- Create: `server/main/xiaozhi-server/tests/test_skill_classifier.py`

- [ ] **Step 1: Write routing tests**

Cover keyword matching, case handling, regex matching, priority, longest match, negative examples, unique deterministic selection, conflict escalation, no-match escalation, malformed classifier output, timeout fallback, low confidence fallback, and returning only a valid candidate ID.

- [ ] **Step 2: Verify failure**

Run both pytest files and expect missing modules.

- [ ] **Step 3: Implement deterministic routing**

Compile regex at bundle load time. Calculate a deterministic candidate score from trigger priority, match length, and device binding priority. Return a Skill only when one candidate outranks the rest without requiring semantic confirmation.

- [ ] **Step 4: Implement the LLM classifier**

Use the active LLM through a small non-streaming JSON classification request containing only candidate IDs, names, descriptions, and examples. Cap output tokens, enforce a short timeout, parse one `skill_id` and numeric confidence, and return no Skill on any error.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: route utterances through device skills`.

### Task 9: Enforce Skill tool isolation in the existing conversation flow

**Files:**
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/unified_tool_handler.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/unified_tool_manager.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/server_mcp/mcp_executor.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/device_mcp/mcp_executor.py`
- Create: `server/main/xiaozhi-server/tests/test_skill_tool_isolation.py`
- Modify: `server/main/xiaozhi-server/tests/test_connection_tool_routing.py`

- [ ] **Step 1: Write isolation and compatibility tests**

Prove unbound tools never reach the model, a selected Skill exposes only mapped tools, direct chat exposes only required system tools, weather/news/search no longer depend on the hardcoded keyword tuple, role changes do not replace the device bundle, and `tools_enabled=false` still disables all optional tools.

- [ ] **Step 2: Verify failure**

Run the focused Python tests.

- [ ] **Step 3: Load the bundle when private device config initializes**

Use the authenticated device ID already present on the connection. Keep the bundle snapshot for one dialogue turn. Invalidate it on reconnect and when manager API reports a newer configuration version.

- [ ] **Step 4: Replace `_select_functions_for_query`**

Call the Skill router, inject the selected Skill execution prompt into the turn context, and filter `ToolManager.get_all_tools()` by the Skill tool names. Preserve `handle_exit_intent` as a required system tool. Remove the static Chinese keyword gate after official Skill migration is active.

Emit `skill.matched`, `skill.not_matched`, `skill.failed`, and `skill.completed` through the existing device debug event channel. Include only Skill ID, published version, trigger mode, selected tool names, result class, and duration. Do not include full prompts, secrets, raw model messages, or internal reasoning.

- [ ] **Step 5: Send device tool snapshots**

After device MCP `tools/list` completes, asynchronously post sanitized tool names and schemas to the internal snapshot endpoint. Snapshot failure must not prevent device conversation.

- [ ] **Step 6: Run tests and commit**

Commit with `feat: isolate tools by device skill`.

### Task 10: Replace local server MCP settings with backend-managed connections

**Files:**
- Modify: `server/main/xiaozhi-server/core/providers/tools/server_mcp/mcp_manager.py`
- Modify: `server/main/xiaozhi-server/core/providers/tools/server_mcp/mcp_client.py`
- Create: `server/main/xiaozhi-server/core/providers/tools/server_mcp/config_resolver.py`
- Create: `server/main/xiaozhi-server/tests/test_server_mcp_capability_config.py`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/McpCapabilityService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/McpCapabilityServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/McpSyncDTO.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/McpCapabilityServiceImplTest.java`

- [ ] **Step 1: Write MCP configuration and whitelist tests**

Prove only device-authorized MCP servers initialize, only whitelisted tools are returned, secret references resolve in memory, local JSON is ignored when backend configuration is active, tool drift disables newly appeared tools, and stdio commands must match an approved command template.

- [ ] **Step 2: Verify failures in Java and Python**

Run the focused Maven and pytest tests.

- [ ] **Step 3: Implement backend MCP sync state**

Persist synchronized tool schemas and differences. Connection testing updates health fields but does not grant tools. Tool approval writes the whitelist used in effective bundles.

- [ ] **Step 4: Implement Python backend resolver**

Build `ServerMCPClient` instances from the selected device bundle rather than `data/.mcp_server_settings.json`. Resolve secrets immediately before initialization and retain them only in client memory. Keep a one-time import command for the old JSON, but never merge both sources at runtime.

- [ ] **Step 5: Run tests and commit**

Commit with `feat: manage server mcp connections centrally`.

### Task 11: Add the typed console capability API

**Files:**
- Create: `server/main/companion-console/src/api/capabilities.ts`
- Create: `server/main/companion-console/src/api/capabilities.test.ts`

- [ ] **Step 1: Write parser and request tests**

Cover paged capability parsing, Skill triggers and tools, device bindings, publish and status mutations, configured secret markers, MCP snapshots, and protocol errors that redact secret-like fields.

- [ ] **Step 2: Verify failure**

Run `cd server/main/companion-console && npm test -- src/api/capabilities.test.ts`.

- [ ] **Step 3: Implement strict API types and parsers**

Follow the defensive parsing style in `xiaozhiModels.ts`. Export request functions for all administrator and device endpoints. Keep secret values write-only.

- [ ] **Step 4: Run tests and commit**

Commit with `feat(console): add capability api client`.

### Task 12: Build the administrator capability center

**Files:**
- Create: `server/main/companion-console/src/pages/admin/CapabilityManagementPage.tsx`
- Create: `server/main/companion-console/src/pages/admin/CapabilityManagementPage.test.tsx`
- Create: `server/main/companion-console/src/pages/admin/SkillEditorModal.tsx`
- Create: `server/main/companion-console/src/pages/admin/SkillEditorModal.test.tsx`
- Create: `server/main/companion-console/src/pages/admin/McpEditorModal.tsx`
- Create: `server/main/companion-console/src/pages/admin/McpEditorModal.test.tsx`
- Modify: `server/main/companion-console/src/app/navigation.tsx`
- Modify: `server/main/companion-console/src/app/router.tsx`
- Modify: `server/main/companion-console/src/pages/admin/AdminRoutes.test.tsx`

- [ ] **Step 1: Write page tests**

Test navigation visibility, type/status filtering, Skill creation, mixed trigger editing, existing-tool selection, prompt editing, publish confirmation, immutable published-version display, Plugin availability, MCP secret non-display, connection state, tool whitelist, and server error recovery.

- [ ] **Step 2: Verify failure**

Run the focused Vitest files.

- [ ] **Step 3: Implement the capability list and editors**

Use `ProTable`, focused modal components, and existing admin error helpers. Do not place the entire editor in one page file. Skill code upload controls must not exist. Tool selection accepts only backend catalog items.

- [ ] **Step 4: Register the AI ability navigation route**

Add a super-admin route under the existing `AI 能力` group without changing unrelated routes.

- [ ] **Step 5: Run tests and commit**

Commit with `feat(console): manage skills plugins and mcp`.

### Task 13: Add per-device Skill configuration to the device detail page

**Files:**
- Create: `server/main/companion-console/src/pages/devices/DeviceSkillCard.tsx`
- Create: `server/main/companion-console/src/pages/devices/DeviceSkillCard.test.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.test.tsx`

- [ ] **Step 1: Write device binding UI tests**

Test loading available published Skills, binding and unbinding, latest and fixed version selection, enable switches, allowed override fields, unavailable device-tool reasons, role switching without Skill changes, and preserving existing volume and brightness controls.

- [ ] **Step 2: Verify failure**

Run the two focused Vitest files.

- [ ] **Step 3: Implement a focused device Skill card**

Keep binding state and mutations inside `DeviceSkillCard`. The parent passes only the device ID and capability flags. Use one transactional save call rather than one request per switch.

- [ ] **Step 4: Run tests and commit**

Commit with `feat(console): configure skills per device`.

### Task 14: Add migration audit, compatibility projection, and deprecation behavior

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityMigrationAuditVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityMigrationAuditService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityMigrationAuditServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelProviderServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityMigrationAuditServiceImplTest.java`
- Modify: `server/main/manager-web/src/components/FunctionDialog.vue`

- [ ] **Step 1: Write migration audit and projection tests**

Prove old Plugin list reads project from the new capability catalog, old writes cannot create a second truth, unmapped legacy agent rows appear in an audit report, and runtime config prefers the new device bundle when present.

- [ ] **Step 2: Verify failure**

Run the focused Java tests.

- [ ] **Step 3: Implement read-only compatibility**

Project Plugin metadata from the capability service for legacy readers. Stop emitting `plugins` from the old agent mapping when a device has a capability configuration version. Keep fallback only for devices not yet migrated.

- [ ] **Step 4: Mark the old manager page read-only**

Show a concise migration notice and link to `companion-console`. Remove save controls only after the new capability API and device page tests pass.

- [ ] **Step 5: Run tests and commit**

Commit with `refactor: retire legacy plugin configuration`.

### Task 15: Verify the complete capability center

**Files:**
- Create: `server/main/xiaozhi-server/tests/test_device_skill_end_to_end.py`
- Modify: `server/main/README_en.md`
- Modify: `README.md`

- [ ] **Step 1: Add an end-to-end service test**

Use fake manager API bundles for two devices with different weather locations. Verify identical utterances select the weather Skill but pass distinct defaults, news and search route without the former static keyword gate, role changes keep bindings, unbinding removes the tool next turn, MCP tool whitelists hold, and offline device tools return failure.

- [ ] **Step 2: Run backend verification**

Run the new capability Java test package, existing companion device tests, security tests, and Liquibase contract tests with `mvn -DskipTests=false`.

- [ ] **Step 3: Run Python verification**

Run all new capability tests plus existing unified tool, routing, weather, and audio tests through pytest.

- [ ] **Step 4: Run console verification**

Run `npm test`, `npm run lint`, and `npm run build` in `server/main/companion-console`.

- [ ] **Step 5: Review security and secret evidence**

Search public responses, logs, snapshots, and debug events for secret fields. Confirm arbitrary Skill code and arbitrary stdio commands have no API or UI path.

- [ ] **Step 6: Update documentation and commit**

Document the capability model, per-device binding, Skill routing, Plugin deployment boundary, MCP migration, and operational fallback. Commit with `docs: explain device skill capability center`.

- [ ] **Step 7: Perform the completion audit**

Match every section of `docs/superpowers/specs/2026-08-16-device-capability-skill-center-design.md` to passing tests, API responses, UI behavior, migration evidence, and runtime behavior. Treat missing evidence as incomplete and continue implementation until every completion criterion is proved.
