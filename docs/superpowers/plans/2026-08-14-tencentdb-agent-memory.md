# TencentDB Agent Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a locally deployable TencentDB MemoryCore memory provider with L0–L3 capture and recall, backend-managed memory LLM/Embedding settings that take effect on the next request, strict user/profile isolation, cross-device sharing, and the existing list/correct/delete/clear management flow.

**Architecture:** Run the pinned upstream MemoryCore v2.0.0 image as an internal Compose service with SQLite data in a named volume. `xiaozhi-server` calls only MemoryCore v3 L0–L3 endpoints through a focused async HTTP client; `manager-api` exposes two server-secret-protected OpenAI-compatible paths that resolve `Memory_tencentdb` on every request and forward to the configured LLM or Embedding provider. To compensate for upstream L2/L3 being scoped by `team_id + agent_id`, the provider uses a stable product prefix plus the companion user ID as `team_id`, so every layer remains isolated by user and profile while multiple devices share the same long-term memory.

**Tech Stack:** Docker Compose, `agentmemory/memory-core:1.0.0` pinned by manifest digest, MemoryCore v3 HTTP API, Python 3.10, `httpx` 0.28, aiohttp, pytest, Java 21, Spring Boot 3.4, `java.net.http.HttpClient`, Liquibase, MySQL, Redis, React 19, TypeScript 5.9, Ant Design 5, Vitest.

---

## File map and fixed contracts

`server/main/xiaozhi-server/deploy/tencentdb-memory/tdai-gateway.template.yaml` is the only checked-in MemoryCore configuration template. A tiny checked-in Node renderer validates `TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS` as a positive integer and `TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL` as an absolute HTTP(S) v1 root, replaces the exact dimension and proxy tokens, and writes `/tmp/tdai-gateway.yaml` before MemoryCore starts. This is necessary because MemoryCore v2.0.0 expands YAML environment placeholders as strings, while its Embedding parser accepts dimensions only as a JSON/YAML number. The rendered config uses `deployMode: standalone`, `stateBackend: local`, `storeBackend: sqlite`, `promptMode: chat`, enables capture, extraction, deduplication, scenarios, persona, BM25, remote embeddings and hybrid recall, and disables Skill. Chat and Embedding point at the rendered manager-api proxy root; the proxy API key is the existing `server.secret`, injected into MemoryCore as `TENCENTDB_MEMORY_MODEL_PROXY_KEY`. MemoryCore itself uses a different `TENCENTDB_MEMORY_CORE_KEY` for its Bearer gate.

`server/main/xiaozhi-server/core/providers/memory/tencentdb/client.py` owns transport only. Every POST includes the configured MemoryCore API key as a Bearer credential, `x-tdai-service-id: ai-plush-companion`, and JSON. It accepts only a configured MemoryCore base URL plus fixed method paths; it never accepts a caller-supplied path or upstream credential.

`server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py` owns companion semantics. Its isolation mapping is fixed as follows:

```text
service_id = ai-plush-companion
team_id    = ai-plush-companion:user:{source_user_id}
user_id    = {source_user_id}
agent_id   = {source_profile_id}
session_id = {connection session_id}
task_id    = {source_device_id}
```

The user ID inside `team_id` is intentional. MemoryCore v2.0.0 stores L0/L1 under team/user/agent but scopes L2/L3 profiles by team/agent and ignores `user_id`. A single product-wide `team_id` would therefore mix persona/scenarios for two users using the same profile. Namespacing `team_id` by user preserves the approved no-cross-user rule without changing upstream code. The global `service_id` still supplies the product/instance namespace.

The provider reads only L1 Atomic results in the management UI. `task_id` maps to `source_device_id`, and the current `agent_id` maps to `source_profile_id`. List, update and delete omit `task_id`, so a device can manage the shared profile memory created by another device. Clear enumerates every L0 message, every L1 record and every L2 scenario under the user/profile scope, deletes them in pages/batches, then writes an empty L3 core file. It is repeatable after partial failure.

The model record is exactly `Memory_tencentdb`. Its editable config keys are `type`, `memory_core_url`, `memory_core_api_key`, `llm_base_url`, `llm_api_key`, `llm_model`, `embedding_base_url`, `embedding_api_key`, `embedding_model`, `embedding_dimensions`, and `embedding_send_dimensions`. The manager proxy ignores any inbound `model`, URL or credentials and replaces them from this saved record. Saving, enabling or deleting the record uses the existing model cache invalidation, so the next proxy request sees the new configuration.

The proxy supports JSON OpenAI Chat Completions and Embeddings responses, including ordinary non-streaming JSON and `text/event-stream` chat responses. It forwards only a field allow-list needed by MemoryCore, always overrides `model`, always overrides `Authorization`, and never logs prompt/input/response bodies. The Embedding proxy follows `embedding_send_dimensions`: true injects the configured dimension; false removes the field even if MemoryCore sent it. The normal controller path resolves saved settings; the connection tester calls the same shaping/forwarding methods with a validated merged form override so unsaved edits test the real proxy code without changing global state.

Changing only endpoint, key, or model name takes effect on the next MemoryCore model call. Changing `embedding_dimensions` is different because MemoryCore v2.0.0 constructs SQLite vector tables at startup; the backend test response and deployment guide must say that a dimension change requires restarting only the MemoryCore container so it can rebuild/reindex vectors. This is the sole restart exception.

## Upstream contract fixture

### Task 1: Freeze the supported MemoryCore v2.0.0 contract

**Files:**
- Create: `server/main/xiaozhi-server/tests/fixtures/tencentdb_memory_v3_contract.json`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_contract.py`
- Create: `server/main/xiaozhi-server/deploy/tencentdb-memory/UPSTREAM.md`

- [ ] **Step 1: Write the failing contract test**

Create a fixture containing the supported upstream release, image digest, fixed routes, response collection fields and isolation warning:

```json
{
  "release": "v2.0.0",
  "image": "agentmemory/memory-core:1.0.0@sha256:f9b286246d0e5020a7f0cb011b7074703d10b76b424a834a117482392f7bd424",
  "service_id": "ai-plush-companion",
  "routes": {
    "conversation_add": "/v3/conversation/add",
    "conversation_query": "/v3/conversation/query",
    "conversation_delete": "/v3/conversation/delete",
    "atomic_query": "/v3/atomic/query",
    "atomic_search": "/v3/atomic/search",
    "atomic_update": "/v3/atomic/update",
    "atomic_delete": "/v3/atomic/delete",
    "scenario_list": "/v3/scenario/ls",
    "scenario_remove": "/v3/scenario/rm",
    "core_read": "/v3/core/read",
    "core_write": "/v3/core/write"
  },
  "collections": {
    "conversation_query": "messages",
    "atomic_query": "items",
    "atomic_search": "items",
    "scenario_list": "entries"
  },
  "l2_l3_scope": ["team_id", "agent_id"]
}
```

Create `test_tencentdb_memory_contract.py` and assert that every provider method name expected by later tasks has a fixture route, the image has a digest, and `l2_l3_scope` excludes `user_id`.

- [ ] **Step 2: Run the contract test and verify the fixture is absent**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_contract.py -v
```

Expected: FAIL because the fixture or contract loader does not exist.

- [ ] **Step 3: Add the fixture and upstream note**

Write `UPSTREAM.md` with these exact facts: the supported repository is `TencentCloud/TencentDB-Agent-Memory`, release `v2.0.0`, tag commit `0aff21a`, image tag `1.0.0`, manifest digest from the fixture, AMD64 digest `sha256:e96a0d20ab14388b3961c5ecc1dd26d48277894c050d40c2c0b9180fb937a4cc`, ARM64 digest `sha256:dfc1cfc517f169f3474c1672f27de466d11c4c7933947b908537d2a3a11655ad`, and upgrades require rerunning the contract and Docker smoke tests before changing the digest.

- [ ] **Step 4: Run the contract test**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_contract.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit the frozen upstream boundary**

```bash
git add server/main/xiaozhi-server/tests/fixtures/tencentdb_memory_v3_contract.json \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_contract.py \
  server/main/xiaozhi-server/deploy/tencentdb-memory/UPSTREAM.md
git commit -m "test: freeze TencentDB memory contract"
```

## Backend configuration

### Task 2: Register the TencentDB memory provider and model

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608141000.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608141000-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/TencentDbMemoryMigrationContractTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Create a resource-level test that reads the forward SQL, rollback SQL and master changelog. Assert these strings and JSON field properties are present:

```java
assertTrue(forward.contains("SYSTEM_Memory_tencentdb"));
assertTrue(forward.contains("Memory_tencentdb"));
assertTrue(forward.contains("'Memory', 'tencentdb'"));
assertTrue(forward.contains("\"type\":\"tencentdb\""));
assertTrue(forward.contains("\"key\":\"memory_core_api_key\""));
assertTrue(forward.contains("\"key\":\"llm_api_key\""));
assertTrue(forward.contains("\"key\":\"embedding_api_key\""));
assertTrue(forward.contains("\"type\":\"password\""));
assertTrue(forward.contains("\"key\":\"embedding_send_dimensions\""));
assertTrue(rollback.indexOf("Memory_tencentdb") < rollback.indexOf("SYSTEM_Memory_tencentdb"));
assertTrue(master.contains("id: 202608141000"));
```

Parse the provider `fields` JSON from the SQL and assert the exact key order from the fixed contract. This catches misspelled keys before Liquibase runs.

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryMigrationContractTest test
```

Expected: FAIL because the new migration is absent.

- [ ] **Step 3: Add idempotent forward and rollback SQL**

Use explicit columns and `INSERT ... SELECT ... WHERE NOT EXISTS`, preserving installations that already created either ID. The provider fields JSON must define these controls:

```json
[
  {"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},
  {"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},
  {"key":"llm_base_url","label":"记忆 LLM 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},
  {"key":"llm_api_key","label":"记忆 LLM 密钥","type":"password"},
  {"key":"llm_model","label":"记忆 LLM 模型","type":"string"},
  {"key":"embedding_base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},
  {"key":"embedding_api_key","label":"Embedding 密钥","type":"password"},
  {"key":"embedding_model","label":"Embedding 模型","type":"string"},
  {"key":"embedding_dimensions","label":"向量维度","type":"integer","default":1024},
  {"key":"embedding_send_dimensions","label":"发送 dimensions","type":"boolean","default":true,"help":"关闭后不向不兼容的 Embedding 服务发送 dimensions 字段。"}
]
```

Create `Memory_tencentdb` as enabled but non-default with `sort = 4`, `type = tencentdb`, the internal MemoryCore URL, and empty secrets/upstream settings. Rollback deletes only `Memory_tencentdb` then `SYSTEM_Memory_tencentdb`.

- [ ] **Step 4: Run migration and existing model service tests**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryMigrationContractTest,ModelConfigServiceImplTest,ModelProviderServiceImplTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the model registration**

```bash
git add server/main/manager-api/src/main/resources/db/changelog/202608141000.sql \
  server/main/manager-api/src/main/resources/db/changelog/202608141000-rollback.sql \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/TencentDbMemoryMigrationContractTest.java
git commit -m "feat: register TencentDB memory model"
```

### Task 3: Resolve live TencentDB model configuration safely

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettings.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryRuntimeSettings.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImplTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/controller/ModelControllerConnectionTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/ModelConfigService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: Write failing settings tests**

Cover successful parsing, disabled/missing model rejection, missing positive dimensions, and cache freshness:

```java
@Test
void readsEveryRequestThroughTheExistingModelCache() {
    when(models.getModelByIdFromCache("Memory_tencentdb"))
            .thenReturn(model(config("first-model")))
            .thenReturn(model(config("second-model")));

    assertEquals("first-model", service.requireEnabled().llmModel());
    assertEquals("second-model", service.requireEnabled().llmModel());
    verify(models, times(2)).getModelByIdFromCache("Memory_tencentdb");
}

@Test
void rejectsMissingSecretsWithoutReturningTheirValues() {
    when(models.getModelByIdFromCache("Memory_tencentdb")).thenReturn(model(configWithBlankKeys()));
    IllegalStateException error = assertThrows(IllegalStateException.class, service::requireEnabled);
    assertEquals("TencentDB 记忆模型配置不完整", error.getMessage());
}
```

- [ ] **Step 2: Run the settings test and verify the types are missing**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryModelSettingsServiceImplTest test
```

Expected: test compilation fails because the settings classes do not exist.

- [ ] **Step 3: Implement the immutable settings record and resolver**

The validated proxy record has exactly these fields:

```java
public record TencentDbMemoryModelSettings(
        URI llmBaseUrl,
        String llmApiKey,
        String llmModel,
        URI embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimensions,
        boolean embeddingSendDimensions) {
}
```

The connection-test path uses a superset record so the MemoryCore endpoint and its credential do not leak into the ordinary proxy interface:

```java
public record TencentDbMemoryRuntimeSettings(
        URI memoryCoreUrl,
        String memoryCoreApiKey,
        TencentDbMemoryModelSettings modelSettings) {
}
```

Put the shared parsing logic in the settings service as `parseModelSettings(JSONObject config)` and `parseRuntime(JSONObject config)`. `parseRuntime` validates `memory_core_url` and `memory_core_api_key`, then delegates all LLM and Embedding fields to `parseModelSettings`. `requireEnabled()` resolves `Memory_tencentdb` and returns `parseModelSettings(config)`. The connection-test service merges the saved and submitted JSON first, then calls `parseRuntime(runtime)` and passes the resulting `TencentDbMemoryRuntimeSettings` to the staged tester. This keeps both paths on the same model URL, secret, model and dimensions validation without making ordinary proxy calls depend on MemoryCore availability settings.

`requireEnabled()` calls `getModelByIdFromCache("Memory_tencentdb")` on every invocation, verifies type `Memory`, `isEnabled == 1`, config type `tencentdb`, absolute HTTP(S) upstream URIs, nonblank upstream keys/models, and positive dimensions. `memory_core_url` and `memory_core_api_key` are parsed by the connection-test path, not retained by the model proxy settings. Do not add a second cache.

Also change `ModelController.enableModelConfig` to call a new public `modelConfigService.evictModelCache(id)` after `updateById`, then add a regression assertion that enable/disable clears `Memory_tencentdb`. Existing edit/delete paths already clear it.

- [ ] **Step 4: Run settings, controller and cache tests**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryModelSettingsServiceImplTest,ModelControllerConnectionTest,ModelConfigServiceImplTest test
```

Expected: PASS.

- [ ] **Step 5: Commit live config resolution**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/controller/ModelControllerConnectionTest.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/model/service/ModelConfigService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java
git commit -m "feat: resolve live TencentDB memory settings"
```

### Task 4: Add the authenticated OpenAI-compatible model proxy

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryUpstreamClient.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyControllerTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyServiceImplTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/security/config/ShiroConfigTest.java`

- [ ] **Step 1: Write failing proxy service tests**

Use the JDK lightweight `HttpServer` as a fake upstream. Assert that chat requests preserve `messages`, `temperature`, `max_tokens`, `tools`, `tool_choice`, `response_format`, `stream`, `stop`, `seed`, `top_p`, `frequency_penalty`, and `presence_penalty`; remove unknown fields; replace the incoming model; replace Authorization; and join the upstream path safely:

```java
assertEquals("configured-memory-llm", forwarded.getString("model"));
assertEquals("Bearer llm-secret", capturedAuthorization.get());
assertFalse(forwarded.containsKey("base_url"));
assertFalse(forwarded.containsKey("api_key"));
assertFalse(forwarded.containsKey("evil"));
assertEquals("/v1/chat/completions", capturedPath.get());
```

Assert embeddings preserve only `input`, `encoding_format`, and `user`; override `model`; inject the configured dimension when enabled; remove inbound `dimensions` when disabled; and reject a missing input before any network call.

Add an SSE test whose fake upstream returns `Content-Type: text/event-stream` in two delayed chunks. Assert the service exposes the first chunk before the fake upstream sends the second and closes both streams on disconnect.

- [ ] **Step 2: Write failing controller and security tests**

Controller tests call both exact routes and assert JSON status/body/content type and streaming content type. Security tests assert this Shiro rule precedes `/**`:

```java
assertEquals("server", filterMap.get("/internal/tencentdb-memory-model/**"));
```

Also assert an absent or wrong Bearer secret returns 401 through `ServerSecretFilter` before the controller is invoked.

- [ ] **Step 3: Run the proxy tests and verify they fail**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryModelProxyControllerTest,TencentDbMemoryModelProxyServiceImplTest,ShiroConfigTest test
```

Expected: test compilation fails because the proxy classes and route are absent.

- [ ] **Step 4: Implement strict request shaping and response streaming**

`TencentDbMemoryUpstreamClient` owns one injected `HttpClient` and one method:

```java
HttpResponse<InputStream> post(URI target, String apiKey, byte[] json, Duration timeout)
```

The service chooses the fixed request kind from the controller method, never from request JSON. Its public controller methods resolve saved settings, while package-private test methods accept a `TencentDbMemoryModelSettings` override produced by the connection-test validator. Build target URLs by normalizing the configured v1 base URL and appending only `/chat/completions` or `/embeddings`. Reject non-2xx upstream responses with a sanitized `502` response containing the status only. Preserve upstream `Content-Type`, `x-request-id` and `openai-*` response headers; strip cookies and hop-by-hop headers.

Log only request kind, upstream host, configured model, elapsed milliseconds, status, response content type and provider request ID. Never log Authorization, keys, messages, Embedding inputs or response bodies.

- [ ] **Step 5: Run proxy and security tests**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryModelProxyControllerTest,TencentDbMemoryModelProxyServiceImplTest,ShiroConfigTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the model proxy**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/security/config/ShiroConfigTest.java
git commit -m "feat: proxy TencentDB memory models"
```

### Task 5: Add the three-stage TencentDB connection test

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryConnectionTester.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryConnectionTesterImpl.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryConnectionTesterImplTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java`

- [ ] **Step 1: Write failing staged connection tests**

Use three fake endpoints and assert call order and exact failure labels:

```java
assertEquals("MemoryCore 可用，记忆 LLM 可用，Embedding 可用", result.getMessage());
assertEquals("MemoryCore 连接失败: HTTP 503", coreFailure.getMessage());
assertEquals("记忆 LLM 连接失败: HTTP 401", llmFailure.getMessage());
assertEquals("Embedding 连接失败: 向量维度不符，期望 1024，实际 1536", embeddingFailure.getMessage());
```

MemoryCore test appends `/health` to the configured `memory_core_url` using the merged backend model form and validates the URL as an absolute HTTP(S) root before sending. It does not accept redirect headers or any secondary URL inside the request body. LLM and Embedding tests call the real proxy service with the validated merged form override. Chat uses a minimal non-streaming request. Embedding uses `input: ["连接测试"]`, applies `embedding_send_dimensions`, and verifies the returned vector length.

- [ ] **Step 2: Run the staged tests and verify failure**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryConnectionTesterImplTest,ModelConnectionTestServiceImplTest test
```

Expected: FAIL because Memory/tencentdb is still reported unsupported.

- [ ] **Step 3: Route Memory/tencentdb to the staged tester**

Merge saved and submitted configuration with the existing blank-secret preservation rule, parse it with the same validator used by the proxy, then call the staged tester. Keep current OpenAI LLM/VLLM behavior unchanged. The tester receives the merged runtime directly so an unsaved form can be tested without changing the global proxy configuration.

Use this exact interface boundary:

```java
public interface TencentDbMemoryConnectionTester {
    CompanionModelTestVO test(TencentDbMemoryRuntimeSettings runtime);
}
```

`TencentDbMemoryConnectionTesterImpl` calls the package-private proxy service overloads with `runtime.modelSettings()`. It uses `runtime.memoryCoreUrl()` and `runtime.memoryCoreApiKey()` only for the health request.

Return the elapsed duration across all completed stages. Do not include URLs, keys, request bodies or upstream error bodies in the message.

- [ ] **Step 4: Run the connection tests**

Run:

```bash
cd server/main/manager-api
mvn -Dtest=TencentDbMemoryConnectionTesterImplTest,ModelConnectionTestServiceImplTest,ModelControllerConnectionTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the backend connection test**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb \
  server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java
git commit -m "feat: test TencentDB memory connections"
```

### Task 6: Enable TencentDB testing and credential editing in the companion console

**Files:**
- Modify: `server/main/companion-console/src/pages/models/modelEditorMetadata.ts`
- Modify: `server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts`
- Modify: `server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`

- [ ] **Step 1: Add failing capability and rendering tests**

Change the capability test to assert:

```ts
expect(canTestModelConnection('Memory', 'tencentdb')).toBe(true)
expect(canTestModelConnection('Memory', 'mem0ai')).toBe(false)
```

Add a field-rendering test with the migration field metadata. Assert all three credential fields use password inputs, `embedding_send_dimensions` uses a switch, `embedding_dimensions` uses a numeric input, and saved secret labels say leaving blank preserves them.

Add a management-page test that switches to Memory, selects TencentDB, opens the model drawer and sees the `测试连接` button.

- [ ] **Step 2: Run focused frontend tests and verify failure**

Run:

```bash
cd server/main/companion-console
npm test -- --run \
  src/pages/models/modelEditorMetadata.test.ts \
  src/pages/models/ModelFieldEditor.test.tsx \
  src/pages/models/ModelManagementPage.test.tsx
```

Expected: FAIL because Memory/tencentdb is not testable yet.

- [ ] **Step 3: Extend the capability rule only**

Implement:

```ts
export function canTestModelConnection(modelType: ModelType, providerCode?: string) {
  const provider = providerCode?.toLowerCase()
  return ((modelType === 'LLM' || modelType === 'VLLM') && provider === 'openai')
    || (modelType === 'Memory' && provider === 'tencentdb')
}
```

No provider-specific form component is needed because the existing metadata renderer already handles password, boolean, integer, help text and leave-blank secret retention.

- [ ] **Step 4: Run the focused frontend tests**

Run:

```bash
cd server/main/companion-console
npm test -- --run \
  src/pages/models/modelEditorMetadata.test.ts \
  src/pages/models/ModelFieldEditor.test.tsx \
  src/pages/models/ModelManagementPage.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit the console capability**

```bash
git add server/main/companion-console/src/pages/models/modelEditorMetadata.ts \
  server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts \
  server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx \
  server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx
git commit -m "feat(console): configure TencentDB memory"
```

## MemoryCore deployment

### Task 7: Add the standalone MemoryCore gateway configuration

**Files:**
- Create: `server/main/xiaozhi-server/deploy/tencentdb-memory/tdai-gateway.template.yaml`
- Create: `server/main/xiaozhi-server/deploy/tencentdb-memory/render-gateway-config.mjs`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_gateway_config.py`

- [ ] **Step 1: Write the failing YAML contract test**

Run the renderer into a temporary path with `TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS=1024` and `TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL=http://xiaozhi-esp32-server-web:8002/xiaozhi/internal/tencentdb-memory-model/v1`, load the rendered YAML, and assert this exact operational contract:

```python
assert config["deployMode"] == "standalone"
assert config["stateBackend"] == "local"
assert config["server"] == {"host": "0.0.0.0", "port": 8420, "apiKey": "${TENCENTDB_MEMORY_CORE_KEY}"}
assert config["data"]["baseDir"] == "/data/tdai-memory"
assert config["llm"]["baseUrl"] == "http://xiaozhi-esp32-server-web:8002/xiaozhi/internal/tencentdb-memory-model/v1"
assert config["llm"]["apiKey"] == "${TENCENTDB_MEMORY_MODEL_PROXY_KEY}"
assert config["llm"]["model"] == "memory-llm-proxy"
assert config["memory"]["promptMode"] == "chat"
assert config["memory"]["capture"]["enabled"] is True
assert config["memory"]["extraction"]["enabled"] is True
assert config["memory"]["extraction"]["enableDedup"] is True
assert config["memory"]["persona"]["triggerEveryN"] == 20
assert config["memory"]["pipeline"]["everyNConversations"] == 1
assert config["memory"]["pipeline"]["enableWarmup"] is True
assert config["memory"]["recall"]["strategy"] == "hybrid"
assert config["memory"]["storeBackend"] == "sqlite"
assert config["memory"]["embedding"]["provider"] == "openai"
assert config["memory"]["embedding"]["baseUrl"] == "http://xiaozhi-esp32-server-web:8002/xiaozhi/internal/tencentdb-memory-model/v1"
assert config["memory"]["embedding"]["dimensions"] == 1024
assert config["memory"]["embedding"]["sendDimensions"] is True
assert config["memory"]["bm25"] == {"enabled": True, "language": "zh"}
assert config["skill"]["enabled"] is False
```

Also assert observability exporters are disabled so the local stack has no undeclared external dependency. Run the renderer with blank, zero, negative, decimal and nonnumeric dimension values and assert it exits nonzero without writing an output file.

- [ ] **Step 2: Run the YAML test and verify the file is missing**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_gateway_config.py -v
```

Expected: FAIL because the template and renderer are absent.

- [ ] **Step 3: Add the gateway YAML**

Use `maxTokens: 8192`, `timeoutMs: 120000`, `maxMemoriesPerSession: 20`, `l1IdleTimeoutSeconds: 60`, `l2DelayAfterL1Seconds: 30`, `l2MinIntervalSeconds: 300`, `l2MaxIntervalSeconds: 1800`, recall `maxResults: 8`, `scoreThreshold: 0.25`, `timeoutMs: 3000`, model proxy token `__MODEL_PROXY_BASE_URL__` in both model sections, Embedding model placeholder `memory-embedding-proxy`, dimensions token `__EMBEDDING_DIMENSIONS__`, `sendDimensions: true`, and Skill disabled.

The static model is a placeholder required by MemoryCore startup. The manager proxy overrides the actual model name on every request. The renderer writes the configured dimension as an unquoted integer, and it must match the backend value.

- [ ] **Step 4: Run the YAML contract test**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_gateway_config.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit the gateway config**

```bash
git add server/main/xiaozhi-server/deploy/tencentdb-memory/tdai-gateway.template.yaml \
  server/main/xiaozhi-server/deploy/tencentdb-memory/render-gateway-config.mjs \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_gateway_config.py
git commit -m "feat: configure TencentDB MemoryCore"
```

### Task 8: Add MemoryCore to Docker Compose without publishing it

**Files:**
- Modify: `server/main/xiaozhi-server/docker-compose_all.yml`
- Modify: `server/main/xiaozhi-server/docker-compose.yml`
- Create: `server/main/xiaozhi-server/docker-compose.tencentdb-memory.dev.yml`
- Create: `server/main/xiaozhi-server/.env.tencentdb-memory.example`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_compose.py`

- [ ] **Step 1: Write the failing Compose contract test**

Use `yaml.safe_load` and assert both base Compose files define `tencentdb-memory-core` with:

```python
assert service["image"] == "agentmemory/memory-core:1.0.0@sha256:f9b286246d0e5020a7f0cb011b7074703d10b76b424a834a117482392f7bd424"
assert service["restart"] == "unless-stopped"
assert service["expose"] == ["8420"]
assert "ports" not in service
assert service["healthcheck"]["test"] == ["CMD-SHELL", "curl -fsS http://127.0.0.1:8420/health >/dev/null || exit 1"]
assert "tencentdb_memory_data:/data/tdai-memory" in service["volumes"]
assert any("tdai-gateway.template.yaml:/data/config/tdai-gateway.template.yaml:ro" in item for item in service["volumes"])
assert any("render-gateway-config.mjs:/data/config/render-gateway-config.mjs:ro" in item for item in service["volumes"])
assert service["environment"]["TDAI_GATEWAY_CONFIG"] == "/tmp/tdai-gateway.yaml"
assert "render-gateway-config.mjs" in service["command"]
```

Assert MemoryCore depends on the manager-api service being started in the all-in-one file, while xiaozhi-server does not depend on MemoryCore. Assert MemoryCore receives `TENCENTDB_MEMORY_MODEL_PROXY_KEY`, a separate `TENCENTDB_MEMORY_CORE_KEY`, `TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS`, and `TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL`. The manager-api filter reads the authoritative `server.secret` from `sys_params`; no unused proxy-secret environment variable is added to manager-api. The dev override alone publishes `127.0.0.1:8420:8420`.

- [ ] **Step 2: Run the Compose test and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_compose.py -v
```

Expected: FAIL because the service and override are absent.

- [ ] **Step 3: Add the service, volume and environment contract**

In `docker-compose.yml`, include MemoryCore as an optional sibling service so a server-only deployment can start it together with an externally configured manager-api URL. In `docker-compose_all.yml`, put manager-api and MemoryCore on the default internal network. MemoryCore may use short-form `depends_on: [xiaozhi-esp32-server-web]`; readiness is enforced by its own model-call retries and the connection test, not by inventing an unavailable manager-api health route. Override the image command with a shell that runs the renderer and then `exec node --import tsx src/gateway/server.ts`. Do not add MemoryCore to the Python server's `depends_on`.

The example env file contains blank values only:

```dotenv
TENCENTDB_MEMORY_CORE_KEY=
TENCENTDB_MEMORY_MODEL_PROXY_KEY=
TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS=1024
TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL=http://xiaozhi-esp32-server-web:8002/xiaozhi/internal/tencentdb-memory-model/v1
```

Add comments saying the model proxy key must equal `server.secret`, while the MemoryCore key must be different. The all-in-one Compose file supplies the internal proxy URL by default. The server-only file defaults to `http://host.docker.internal:8002/xiaozhi/internal/tencentdb-memory-model/v1`, adds `host.docker.internal:host-gateway`, and allows an external manager-api URL override.

- [ ] **Step 4: Render both production and development Compose configurations**

Run:

```bash
cd server/main/xiaozhi-server
TENCENTDB_MEMORY_CORE_KEY=test-core \
TENCENTDB_MEMORY_MODEL_PROXY_KEY=test-proxy \
docker compose -f docker-compose_all.yml config >/tmp/xiaozhi-compose-all.yaml

TENCENTDB_MEMORY_CORE_KEY=test-core \
TENCENTDB_MEMORY_MODEL_PROXY_KEY=test-proxy \
docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml config \
  >/tmp/xiaozhi-compose-dev.yaml

python -m pytest tests/test_tencentdb_memory_compose.py -v
```

Expected: both `docker compose config` commands exit 0 and the test passes.

- [ ] **Step 5: Commit the deployment unit**

```bash
git add server/main/xiaozhi-server/docker-compose_all.yml \
  server/main/xiaozhi-server/docker-compose.yml \
  server/main/xiaozhi-server/docker-compose.tencentdb-memory.dev.yml \
  server/main/xiaozhi-server/.env.tencentdb-memory.example \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_compose.py
git commit -m "feat: deploy TencentDB MemoryCore"
```

## Python MemoryCore integration

### Task 9: Build the focused async v3 client

**Files:**
- Create: `server/main/xiaozhi-server/core/providers/memory/tencentdb/__init__.py`
- Create: `server/main/xiaozhi-server/core/providers/memory/tencentdb/client.py`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_client.py`

- [ ] **Step 1: Write failing transport and envelope tests**

Use `httpx.MockTransport` to assert exact headers, paths, JSON bodies, timeout handling, 401/422/503 errors, non-JSON errors, and request IDs. Include this strict-isolation assertion:

```python
await client.atomic_search(
    isolation={
        "team_id": "ai-plush-companion:user:7",
        "user_id": "7",
        "agent_id": "profile-a",
    },
    query="喜欢什么",
    limit=8,
)

assert captured.json == {
    "team_id": "ai-plush-companion:user:7",
    "user_id": "7",
    "agent_id": "profile-a",
    "query": "喜欢什么",
    "limit": 8,
}
assert captured.headers["x-tdai-service-id"] == "ai-plush-companion"
```

Add method coverage for conversation add/query/delete, atomic query/search/update/delete, scenario list/remove, and core read/write. Reject missing team/user/agent before network I/O. Reject a route not present in the frozen contract.

- [ ] **Step 2: Run the client tests and verify imports fail**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_client.py -v
```

Expected: collection fails because the client module is absent.

- [ ] **Step 3: Implement transport, envelope decoding and typed method wrappers**

Expose:

```python
class TencentDbMemoryError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, code: int | None = None,
                 request_id: str | None = None, retryable: bool = False): ...

class TencentDbMemoryClient:
    async def health(self) -> dict: ...
    async def conversation_add(self, isolation: dict, session_id: str, messages: list[dict]) -> dict: ...
    async def conversation_query(self, isolation: dict, *, session_id: str | None = None,
                                 limit: int = 100, offset: int = 0) -> dict: ...
    async def conversation_delete(self, isolation: dict, *, message_ids: list[str] | None = None,
                                  session_id: str | None = None) -> dict: ...
    async def atomic_query(self, isolation: dict, *, limit: int = 100, offset: int = 0) -> dict: ...
    async def atomic_search(self, isolation: dict, query: str, *, limit: int = 8) -> dict: ...
    async def atomic_update(self, isolation: dict, memory_id: str, content: str) -> dict: ...
    async def atomic_delete(self, isolation: dict, ids: list[str]) -> dict: ...
    async def scenario_list(self, isolation: dict) -> dict: ...
    async def scenario_remove(self, isolation: dict, path: str) -> dict: ...
    async def core_read(self, isolation: dict) -> dict: ...
    async def core_write(self, isolation: dict, content: str) -> dict: ...
```

Keep one lazily created `httpx.AsyncClient` per provider instance. `health()` is the only GET. Decode only MemoryCore's `{code,message,request_id,data}` envelope; return `data` plus `_request_id` for diagnostics.

- [ ] **Step 4: Run the client tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_client.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit the v3 client**

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_client.py
git commit -m "feat: add TencentDB memory v3 client"
```

### Task 10: Implement identity mapping and L0 capture with retry dedupe

**Files:**
- Create: `server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_capture.py`
- Modify: `server/main/xiaozhi-server/core/providers/memory/base.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/api/companion_memory_handler.py`
- Modify: `server/main/xiaozhi-server/tests/test_companion_conversation.py`
- Modify: `server/main/xiaozhi-server/tests/test_memory_handler.py`
- Modify: `server/main/xiaozhi-server/tests/test_companion_memory_management.py`

- [ ] **Step 1: Write failing identity and message conversion tests**

Initialize the provider with `source_user_id`, `source_profile_id`, and `source_device_id`. Assert:

```python
assert provider.isolation == {
    "team_id": "ai-plush-companion:user:7",
    "user_id": "7",
    "agent_id": "profile-a",
}
assert provider.task_id == "device-a"
```

Create dialogue messages containing system, tool, temporary, `None`, JSON-wrapped ASR text, user and assistant content. Assert only non-temporary user/assistant messages with nonblank content are sent, each is at most 8192 characters, each has one UTC timestamp captured at conversion time, `session_id` is required, and the request body includes `task_id: device-a`.

Add a cross-device test: providers for `device-a` and `device-b` with the same user/profile have equal team/user/agent isolation but different task IDs. Add cross-user and cross-profile tests proving the isolation changes.

- [ ] **Step 2: Write the failing uncertain-write dedupe test**

Simulate `conversation_add` raising a timeout after the fake server persisted the messages. The provider must query the same session tail with `limit = len(outgoing)` and compare normalized `role`, `content`, and timestamp tolerance. If it matches, return success without a second add. If it does not match, retry once. Definite HTTP 4xx errors are not retried.

```python
client.conversation_add.side_effect = [httpx.ReadTimeout("unknown"), {"accepted_ids": ["m1", "m2"]}]
client.conversation_query.return_value = {"messages": persisted_tail, "total": 2}

await provider.save_memory(messages, "session-a")

client.conversation_add.assert_awaited_once()
```

- [ ] **Step 3: Run the capture tests and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_provider_capture.py \
  tests/test_companion_conversation.py \
  tests/test_memory_handler.py \
  tests/test_companion_memory_management.py -v
```

Expected: FAIL because `tencentdb.MemoryProvider` and `source_user_id` propagation are absent.

- [ ] **Step 4: Implement capture and trusted identity propagation**

Extend `MemoryProviderBase.init_memory` only by retaining trusted metadata. Add `source_user_id` in both `ConnectionHandler` and `CompanionMemoryHandler`; never accept it from an external memory request body.

`MemoryProvider.__init__` reads `memory_core_url`, `memory_core_api_key`, optional `request_timeout_seconds` defaulting to 4, and the fixed service ID. `save_memory` catches and logs failures so session close remains successful. Do not wait for L1/L2/L3 extraction after a successful L0 add.

Expose a small diagnostic snapshot from the provider through `get_diagnostics()`:

```python
{
    "request_id": request_id,
    "layer_hits": {},
    "degraded_reason": None,
}
```

Do not include memory content in diagnostics.

- [ ] **Step 5: Run capture and existing companion tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_provider_capture.py \
  tests/test_companion_conversation.py \
  tests/test_memory_handler.py \
  tests/test_companion_memory_management.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit L0 capture and identity mapping**

```bash
git add server/main/xiaozhi-server/core/providers/memory/base.py \
  server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py \
  server/main/xiaozhi-server/core/connection.py \
  server/main/xiaozhi-server/core/api/companion_memory_handler.py \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_capture.py \
  server/main/xiaozhi-server/tests/test_companion_conversation.py \
  server/main/xiaozhi-server/tests/test_memory_handler.py \
  server/main/xiaozhi-server/tests/test_companion_memory_management.py
git commit -m "feat: capture TencentDB companion memory"
```

### Task 11: Implement combined L1, L2 and L3 recall with budgets

**Files:**
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_recall.py`
- Modify: `server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/tests/test_debug_event_instrumentation.py`
- Modify: `server/main/xiaozhi-server/tests/test_debug_event_details.py`

- [ ] **Step 1: Write failing recall formatting and budget tests**

Mock concurrent Atomic Search, Scenario List and Core Read responses. Assert no legacy `/recall` or L0 search is called. The output format is fixed:

```text
[原子记忆]
- 用户喜欢草莓
- 用户住在杭州

[相关场景]
- 睡前习惯：通常在晚上十点听海浪声

[用户画像]
用户偏好温和简短的回应。
```

Use these budgets: L1 up to 8 entries and 3000 characters, L2 up to 5 non-directory summaries and 1500 characters, L3 up to 2000 characters, final output up to 6000 characters. Skip blank/malformed entries and deduplicate identical L1 content.

Add tests that all three calls omit `task_id`, so recall is shared across devices, while still carrying team/user/agent. Add one failure and timeout per layer: successful layers remain in the output; if all layers fail, return an empty string within 4 seconds.

- [ ] **Step 2: Run the recall tests and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_provider_recall.py -v
```

Expected: FAIL because recall is not implemented.

- [ ] **Step 3: Implement concurrent layered recall and diagnostics**

Use `asyncio.gather(..., return_exceptions=True)` with per-call `asyncio.timeout(3)`. L2 consumes only `entry.summary`; do not read every scenario body in the live request path. L3 accepts `data.content` or an absent core file as empty.

Set the provider diagnostic snapshot returned by `get_diagnostics()` after each query:

```python
{
    "request_id": first_nonblank_request_id,
    "recall_strategy": "atomic_hybrid+scenario_navigation+core",
    "layer_hits": {"L1": 2, "L2": 1, "L3": 1},
    "degraded_reason": None
}
```

On partial failure, `degraded_reason` is a comma-separated layer/class summary without content. Change connection debug events to call `get_diagnostics()` and include this snapshot. Remove raw `query` and raw `result` from memory debug details. Keep only query length, result length, hit flag, request ID, strategy, layer counts and degraded reason.

- [ ] **Step 4: Run recall and debug-event tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_provider_recall.py \
  tests/test_debug_event_instrumentation.py \
  tests/test_debug_event_details.py -v
```

Expected: PASS and no test expects raw recalled text in debug details.

- [ ] **Step 5: Commit layered recall**

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py \
  server/main/xiaozhi-server/core/connection.py \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_recall.py \
  server/main/xiaozhi-server/tests/test_debug_event_instrumentation.py \
  server/main/xiaozhi-server/tests/test_debug_event_details.py
git commit -m "feat: recall TencentDB memory layers"
```

### Task 12: Implement list, correction, deletion and repeatable full clear

**Files:**
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_management.py`
- Modify: `server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py`
- Modify: `server/main/xiaozhi-server/tests/test_companion_memory_management.py`
- Modify: `server/main/xiaozhi-server/tests/test_memory_handler.py`

- [ ] **Step 1: Write failing list/update/delete tests**

List must page Atomic Query with `limit=100`, omit `task_id`, sort descending by `updated_at`, and return:

```python
{
    "id": "atomic-a",
    "content": "用户喜欢草莓",
    "updated_at": "2026-08-14T10:00:00Z",
    "source_device_id": "device-a",
    "source_profile_id": "profile-a",
}
```

Before update/delete, query the user/profile scope and prove the ID is owned. Then call Atomic Update/Delete with team/user/agent and without task/session filtering. Return false for a foreign ID or blank corrected content.

- [ ] **Step 2: Write the failing full-clear test**

Fake two pages of L0 messages across several sessions, two pages of L1 records, L2 entries containing files and directory markers, and an L3 profile. Assert clear:

```text
enumerates L0 messages across all sessions
deletes L0 IDs in chunks of 100
enumerates L1 across all sessions/devices
deletes L1 IDs in chunks of 100
removes each non-directory L2 path
writes empty L3 content last
```

If L2 removal fails after L0/L1 deletion, `clear_memory()` returns false. On a second run with already-empty L0/L1, it resumes L2/L3 and returns true. Never call `/v3/instance/destroy`, delete the Docker volume, or clear another user/profile scope.

- [ ] **Step 3: Run management tests and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_provider_management.py \
  tests/test_companion_memory_management.py \
  tests/test_memory_handler.py -v
```

Expected: FAIL because TencentDB management methods are absent.

- [ ] **Step 4: Implement management methods**

Use collection keys frozen in Task 1. Treat absent L3 as already clear. Exclude L2 directory paths ending in `/`. Paginate until returned count is less than the page size or offset reaches `total`. Cap one operation at 10,000 L0 and 10,000 L1 items; exceeding the cap returns false and logs a count-only diagnostic instead of risking an unbounded request.

`list_memory_items` raises `RuntimeError` when MemoryCore is unavailable so the management handler returns 502 and the UI retains its state. `update_memory_item`, `delete_memory_item`, and `clear_memory` return false on failure.

- [ ] **Step 5: Run management and handler tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_provider_management.py \
  tests/test_companion_memory_management.py \
  tests/test_memory_handler.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit memory management**

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_provider_management.py \
  server/main/xiaozhi-server/tests/test_companion_memory_management.py \
  server/main/xiaozhi-server/tests/test_memory_handler.py
git commit -m "feat: manage TencentDB companion memory"
```

### Task 13: Add local configuration examples without changing existing defaults

**Files:**
- Modify: `server/main/xiaozhi-server/config.yaml`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_local_config.py`

- [ ] **Step 1: Write the failing local-config test**

Assert `selected_module.Memory` remains `nomem`, while the Memory section contains:

```yaml
tencentdb:
  type: tencentdb
  memory_core_url: http://127.0.0.1:8420
  memory_core_api_key: ""
  request_timeout_seconds: 4
```

The test must also assert local mode documentation says `companion_identity.user_id`, `agent_id`, `device_id`, and `memory_namespace` are required when selecting this provider without manager-api.

- [ ] **Step 2: Run the config test and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_local_config.py -v
```

Expected: FAIL because no TencentDB local example exists.

- [ ] **Step 3: Add the provider example**

Add concise comments linking to `deploy/tencentdb-memory/UPSTREAM.md`. Do not put a real key into `config.yaml` or `data/.config.yaml`. Do not switch any existing role or default model.

- [ ] **Step 4: Run the local config test**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_local_config.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit the local example**

```bash
git add server/main/xiaozhi-server/config.yaml \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_local_config.py
git commit -m "docs: add TencentDB memory local config"
```

## Memory management wording

### Task 14: Make shared role scope explicit in user and admin memory UI

**Files:**
- Modify: `server/main/companion-console/src/pages/memories/MemoryPage.tsx`
- Modify: `server/main/companion-console/src/pages/memories/MemoryPage.test.tsx`
- Modify: `server/main/companion-console/src/pages/admin/AdminDeviceMemoryModal.tsx`
- Create: `server/main/companion-console/src/pages/admin/AdminDeviceMemoryModal.test.tsx`

- [ ] **Step 1: Add failing wording tests**

For the user page assert these exact phrases appear when a device is selected:

```text
清空当前角色记忆
会清空该设备当前绑定角色的长期记忆。同一用户使用这个角色的其他设备也会受影响。
该角色的长期记忆已清空
```

For the admin modal assert:

```text
清空绑定角色记忆
会清空该用户在当前绑定角色下的长期记忆，包含其他设备共享的内容。
```

Keep the device selector and source device/profile display unchanged.

- [ ] **Step 2: Run the UI tests and verify old device-scoped text fails**

Run:

```bash
cd server/main/companion-console
npm test -- --run \
  src/pages/memories/MemoryPage.test.tsx \
  src/pages/admin/AdminDeviceMemoryModal.test.tsx
```

Expected: FAIL because the current text says only the selected device is cleared.

- [ ] **Step 3: Replace only the clear-scope wording**

Do not change API paths or mutation behavior. Keep the existing warning that failed operations preserve visible content.

- [ ] **Step 4: Run the UI tests**

Run:

```bash
cd server/main/companion-console
npm test -- --run \
  src/pages/memories/MemoryPage.test.tsx \
  src/pages/admin/AdminDeviceMemoryModal.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit the scope wording**

```bash
git add server/main/companion-console/src/pages/memories/MemoryPage.tsx \
  server/main/companion-console/src/pages/memories/MemoryPage.test.tsx \
  server/main/companion-console/src/pages/admin/AdminDeviceMemoryModal.tsx \
  server/main/companion-console/src/pages/admin/AdminDeviceMemoryModal.test.tsx
git commit -m "fix(console): explain shared role memory scope"
```

## End-to-end verification and operations

### Task 15: Add an isolated integration harness for user/profile/device separation

**Files:**
- Create: `server/main/xiaozhi-server/tests/integration/fake_tencentdb_memory_core.py`
- Create: `server/main/xiaozhi-server/tests/integration/test_tencentdb_memory_provider_flow.py`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyServiceImplTest.java`

- [ ] **Step 1: Build a failing in-process fake MemoryCore contract server**

The fake implements only the fixed v3 endpoints and records requests by team/user/agent/session/task. It deliberately scopes fake L2/L3 by team/agent, matching upstream. It returns upstream-style envelopes and request IDs.

The provider flow test creates:

```text
user 7 + profile A + device A: likes strawberries
user 7 + profile A + device B: asks what they like
user 7 + profile B + device A: likes the moon
user 8 + profile A + device C: likes pinecones
```

Assert device B recalls strawberries, profile B does not, and user 8 does not. Assert list from device B displays device A as the source. Assert correction/delete/clear affect only user 7/profile A.

- [ ] **Step 2: Add a failing proxy freshness integration test**

Use a fake model settings service that returns model `memory-a` on the first request and `memory-b` on the second. Call the real proxy service twice and assert the fake upstream sees the new model on request two without recreating the proxy service. Add a parallel Embedding assertion. Add the dimension exception assertion: changing configured dimension updates the proxy body, but the test reports MemoryCore restart required before a production vector-table change.

- [ ] **Step 3: Run the integration tests and verify missing harness failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/integration/test_tencentdb_memory_provider_flow.py -v

cd ../manager-api
mvn -Dtest=TencentDbMemoryModelProxyServiceImplTest test
```

Expected: FAIL until the fake server and freshness cases are added.

- [ ] **Step 4: Complete the isolated integration harness**

Use ephemeral loopback ports and no Docker dependency. Keep response fixtures minimal and aligned with Task 1. The fake must reject a missing user/profile isolation field and expose every captured request for assertions.

- [ ] **Step 5: Run the integration tests**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/integration/test_tencentdb_memory_provider_flow.py -v

cd ../manager-api
mvn -Dtest=TencentDbMemoryModelProxyServiceImplTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the integration harness**

```bash
git add server/main/xiaozhi-server/tests/integration \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelProxyServiceImplTest.java
git commit -m "test: verify TencentDB memory isolation"
```

### Task 16: Document local start, server deployment, backup, upgrade and purge

**Files:**
- Create: `server/main/xiaozhi-server/docs/tencentdb-agent-memory.md`
- Create: `server/main/xiaozhi-server/deploy/tencentdb-memory/smoke-test.sh`
- Create: `server/main/xiaozhi-server/tests/test_tencentdb_memory_operations_docs.py`

- [ ] **Step 1: Write the failing documentation contract test**

Assert the guide contains these commands and warnings:

```text
docker compose -f docker-compose_all.yml up -d tencentdb-memory-core
docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml up -d tencentdb-memory-core
docker compose -f docker-compose_all.yml stop tencentdb-memory-core
docker run --rm -v xiaozhi-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar czf /backup/tencentdb-memory-backup.tgz -C /data .
docker run --rm -v xiaozhi-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar xzf /backup/tencentdb-memory-backup.tgz -C /data
docker volume rm xiaozhi-server_tencentdb_memory_data
```

The purge section must say deletion is irreversible and require stopping MemoryCore first. The upgrade section must require backup, digest update, contract tests, smoke tests and rollback by restoring the previous digest plus volume backup. The dimension-change section must say restart only MemoryCore and expect vector reindexing; endpoint/key/model-name edits apply on the next request.

- [ ] **Step 2: Run the docs test and verify failure**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_operations_docs.py -v
```

Expected: FAIL because the operations guide is absent.

- [ ] **Step 3: Write the guide and smoke script**

The guide covers copying `.env.tencentdb-memory.example`, making the proxy key equal `server.secret`, making the core key different, starting the full stack, using the loopback override only for diagnostics, configuring `Memory_tencentdb` in the backend, assigning it to a test profile, interpreting L0/L1/L2/L3 delays, backing up while MemoryCore is stopped, restoring into an empty volume, upgrading the pinned image, stopping without data loss, and destructive purge.

`smoke-test.sh` accepts `MEMORY_CORE_URL`, `MEMORY_CORE_API_KEY`, and optional `SERVICE_ID`. It performs health, isolated conversation add/query, Atomic query polling, scenario list, core read, container-restart persistence instructions, and exits nonzero on any envelope code other than zero. It prints IDs/counts only, never content or keys.

- [ ] **Step 4: Run docs and shell validation**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_tencentdb_memory_operations_docs.py -v
bash -n deploy/tencentdb-memory/smoke-test.sh
```

Expected: PASS with no shell output.

- [ ] **Step 5: Commit operations documentation**

```bash
git add server/main/xiaozhi-server/docs/tencentdb-agent-memory.md \
  server/main/xiaozhi-server/deploy/tencentdb-memory/smoke-test.sh \
  server/main/xiaozhi-server/tests/test_tencentdb_memory_operations_docs.py
git commit -m "docs: operate TencentDB companion memory"
```

### Task 17: Run full verification and a real Docker smoke test

**Files:**
- Modify only files from earlier tasks when verification finds a defect

- [ ] **Step 1: Run the complete Python memory and companion suite**

Run:

```bash
cd server/main/xiaozhi-server
python -m pytest \
  tests/test_tencentdb_memory_contract.py \
  tests/test_tencentdb_memory_gateway_config.py \
  tests/test_tencentdb_memory_compose.py \
  tests/test_tencentdb_memory_client.py \
  tests/test_tencentdb_memory_provider_capture.py \
  tests/test_tencentdb_memory_provider_recall.py \
  tests/test_tencentdb_memory_provider_management.py \
  tests/test_tencentdb_memory_local_config.py \
  tests/test_tencentdb_memory_operations_docs.py \
  tests/test_companion_conversation.py \
  tests/test_companion_memory_management.py \
  tests/test_memory_handler.py \
  tests/test_debug_event_instrumentation.py \
  tests/test_debug_event_details.py \
  tests/integration/test_tencentdb_memory_provider_flow.py -v
```

Expected: PASS.

- [ ] **Step 2: Run the complete manager-api model and memory suite**

Run:

```bash
cd server/main/manager-api
mvn -Dtest='TencentDbMemory*,ModelConnectionTestServiceImplTest,ModelControllerConnectionTest,ModelConfigServiceImplTest,ModelProviderServiceImplTest,ShiroConfigTest,CompanionMemoryServiceImplTest,CompanionMemoryControllerTest' test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run the complete companion-console model and memory suite**

Run:

```bash
cd server/main/companion-console
npm test -- --run \
  src/pages/models/modelEditorMetadata.test.ts \
  src/pages/models/ModelFieldEditor.test.tsx \
  src/pages/models/ModelManagementPage.test.tsx \
  src/pages/memories/MemoryPage.test.tsx \
  src/pages/admin/AdminDeviceMemoryModal.test.tsx
npm run build
```

Expected: focused tests pass and the production build exits 0.

- [ ] **Step 4: Validate rendered Compose configurations**

Run:

```bash
cd server/main/xiaozhi-server
TENCENTDB_MEMORY_CORE_KEY=test-core \
TENCENTDB_MEMORY_MODEL_PROXY_KEY=test-proxy \
docker compose -f docker-compose_all.yml config >/tmp/xiaozhi-compose-all.yaml

TENCENTDB_MEMORY_CORE_KEY=test-core \
TENCENTDB_MEMORY_MODEL_PROXY_KEY=test-proxy \
docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml config \
  >/tmp/xiaozhi-compose-dev.yaml
```

Expected: both commands exit 0; production output has no MemoryCore host port and dev output has only `127.0.0.1:8420`.

- [ ] **Step 5: Run the real MemoryCore container smoke test**

Start manager-api with a local fake OpenAI-compatible LLM and Embedding endpoint configured in `Memory_tencentdb`, then run:

```bash
cd server/main/xiaozhi-server
export TENCENTDB_MEMORY_CORE_KEY=test-core
export TENCENTDB_MEMORY_MODEL_PROXY_KEY="$(docker compose -f docker-compose_all.yml exec -T xiaozhi-esp32-server-db mysql -uroot -p123456 -Nse \"SELECT param_value FROM xiaozhi_esp32_server.sys_params WHERE param_code='server.secret' LIMIT 1\")"
test -n "$TENCENTDB_MEMORY_MODEL_PROXY_KEY"
test "$TENCENTDB_MEMORY_MODEL_PROXY_KEY" != null
docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml up -d \
  xiaozhi-esp32-server-db xiaozhi-esp32-server-redis xiaozhi-esp32-server-web tencentdb-memory-core

MEMORY_CORE_URL=http://127.0.0.1:8420 \
MEMORY_CORE_API_KEY="$TENCENTDB_MEMORY_CORE_KEY" \
bash deploy/tencentdb-memory/smoke-test.sh

docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml restart tencentdb-memory-core

MEMORY_CORE_URL=http://127.0.0.1:8420 \
MEMORY_CORE_API_KEY="$TENCENTDB_MEMORY_CORE_KEY" \
bash deploy/tencentdb-memory/smoke-test.sh --verify-existing
```

Expected: health passes, L0 persists across container restart, L1 eventually appears, L2/L3 endpoints are callable, and the script prints no memory content or secrets.

- [ ] **Step 6: Verify BM25 degradation**

Temporarily point the Embedding backend at a controlled 503 fake while keeping MemoryCore and the memory LLM healthy. Add/query a unique keyword and assert Atomic Search still returns the keyword result or reports the strategy degraded to BM25 without breaking the chat/provider call. Restore the Embedding endpoint after the assertion.

- [ ] **Step 7: Verify clean rollback behavior**

Switch the test profile from `Memory_tencentdb` to its former provider, stop `tencentdb-memory-core`, and confirm chat still answers. Restart MemoryCore and confirm its named volume still contains the previous memory. Do not remove the volume.

- [ ] **Step 8: Inspect the final diff and commit verification fixes**

Run:

```bash
git status --short
git diff --check
git diff --stat
```

Expected: no whitespace errors; only task-related paths are staged or committed. If verification required fixes, commit them with:

```bash
git add server/main/xiaozhi-server/core/providers/memory/tencentdb/tencentdb.py
git commit -m "fix: verify TencentDB memory integration"
```

Do not stage or rewrite unrelated user modifications already present in the worktree.
