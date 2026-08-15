# TencentDB Memory Model References Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add managed Embedding models, let TencentDB Memory reference existing LLM and Embedding model IDs, then configure and start the complete local memory stack.

**Architecture:** manager-api remains the only model credential owner. The TencentDB Memory proxy resolves referenced model IDs from the model cache on every request, while provider metadata injects safe ID/name options for both admin frontends. MemoryCore stays in Docker Compose and calls the existing internal OpenAI-compatible proxy.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Liquibase SQL, React 19 with Ant Design, Vue 2 with Element UI, Vitest, Node test runner, Docker Compose, Python pytest.

---

## File map

The database change lives in `server/main/manager-api/src/main/resources/db/changelog/202608160100.sql` with its rollback and master entry. It registers the `Embedding/openai` provider, creates the generic Embedding config skeleton, replaces the visible TencentDB fields with model references, and preserves legacy runtime fields in existing rows.

`TencentDbMemoryModelCatalogService` owns the safe dropdown catalog. `TencentDbMemoryModelSettingsServiceImpl` owns referenced-model resolution and legacy fallback. `OpenAiEmbeddingConnectionTester` owns direct Embedding connection checks. `ModelController` and `ModelConnectionTestServiceImpl` only route requests to those focused services.

The React console adds the `Embedding` model type and renders injected provider options through the existing `ModelFieldEditor`. The Vue manager adds an Embedding navigation item and uses the existing normalized option renderer. Neither frontend receives referenced model credentials.

`deploy/tencentdb-memory/configure-local.sh` creates the ignored local environment file, starts MemoryCore, and prints status without printing secrets. The final local configuration reuses the current `LLM_ChatGLMLLM` credential for a new `Embedding_openai` row and points `Memory_tencentdb` at both model IDs.

### Task 1: Create an isolated implementation worktree

**Files:**
- Worktree: `.worktrees/tencentdb-memory-model-references`
- Branch: `codex/tencentdb-memory-model-references`

- [ ] **Step 1: Read the worktree skill and inspect ownership**

Run:

```bash
sed -n '1,320p' /Users/xiaox/.agents/skills/using-git-worktrees/SKILL.md
git status --short
git worktree list --porcelain
```

Expected: main remains on the current commit and existing uncommitted files are visible only in the main worktree.

- [ ] **Step 2: Create the branch worktree from current HEAD**

Run:

```bash
git worktree add .worktrees/tencentdb-memory-model-references -b codex/tencentdb-memory-model-references HEAD
```

Expected: a clean worktree on `codex/tencentdb-memory-model-references`.

- [ ] **Step 3: Verify the baseline in the worktree**

Run:

```bash
git -C .worktrees/tencentdb-memory-model-references status --short
git -C .worktrees/tencentdb-memory-model-references rev-parse --short HEAD
```

Expected: no status output and the same HEAD as main.

### Task 2: Add the Embedding model migration

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608160100.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608160100-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/TencentDbMemoryMigrationContractTest.java`

- [ ] **Step 1: Write the failing migration contract**

Add assertions that the new migration contains the Embedding provider, generic config, model reference fields, and rollback order:

```java
@Test
void migrationRegistersManagedEmbeddingAndMemoryReferences() throws Exception {
    String forward = Files.readString(CHANGELOG.resolve("202608160100.sql"));
    String rollback = Files.readString(CHANGELOG.resolve("202608160100-rollback.sql"));
    String master = Files.readString(CHANGELOG.resolve("db.changelog-master.yaml"));

    assertTrue(forward.contains("SYSTEM_Embedding_openai"));
    assertTrue(forward.contains("Embedding_openai"));
    assertTrue(forward.contains("'Embedding', 'openai'"));
    assertTrue(forward.contains("\"key\":\"llm_model_id\""));
    assertTrue(forward.contains("\"key\":\"embedding_model_id\""));
    assertTrue(forward.contains("JSON_SET"));
    assertTrue(rollback.indexOf("Embedding_openai") < rollback.indexOf("SYSTEM_Embedding_openai"));
    assertTrue(master.contains("id: 202608160100"));
}
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=TencentDbMemoryMigrationContractTest test
```

Expected: FAIL because `202608160100.sql` does not exist.

- [ ] **Step 3: Add the forward migration**

Create a migration with these concrete records and updates:

```sql
INSERT INTO `ai_model_provider`
    (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
SELECT
    'SYSTEM_Embedding_openai', 'Embedding', 'openai', 'OpenAI 兼容 Embedding',
    '[{"key":"base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"api_key","label":"Embedding 密钥","type":"password"},{"key":"model_name","label":"Embedding 模型","type":"string"},{"key":"dimensions","label":"向量维度","type":"integer","default":1024},{"key":"send_dimensions","label":"发送 dimensions","type":"boolean","default":true}]',
    1, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_provider` WHERE `id` = 'SYSTEM_Embedding_openai');

INSERT INTO `ai_model_config`
    (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `config_json`, `remark`, `sort`, `creator`, `create_date`)
SELECT
    'Embedding_openai', 'Embedding', 'openai', 'OpenAI 兼容 Embedding', 0, 0,
    '{"type":"openai","base_url":"","api_key":"","model_name":"","dimensions":1024,"send_dimensions":true}',
    '供长期记忆和向量检索使用', 1, 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = 'Embedding_openai');

UPDATE `ai_model_provider`
SET `fields` = '[{"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},{"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},{"key":"llm_model_id","label":"记忆 LLM","type":"string","options":[],"help":"选择已启用的 OpenAI 兼容 LLM。"},{"key":"embedding_model_id","label":"Embedding 模型","type":"string","options":[],"help":"选择已启用的 Embedding 模型。"}]',
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_Memory_tencentdb';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
        `config_json`,
        '$.llm_model_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.llm_model_id')), ''),
        '$.embedding_model_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.embedding_model_id')), '')
    ),
    `update_date` = NOW()
WHERE `id` = 'Memory_tencentdb';
```

- [ ] **Step 4: Add rollback and master entry**

Use rollback that restores the legacy TencentDB field metadata, removes the two reference keys with `JSON_REMOVE`, then deletes the Embedding config before the provider:

```sql
UPDATE `ai_model_provider`
SET `fields` = '[{"key":"memory_core_url","label":"MemoryCore 地址","type":"string","default":"http://tencentdb-memory-core:8420","help":"xiaozhi-server 可访问的 MemoryCore 根地址。"},{"key":"memory_core_api_key","label":"MemoryCore 密钥","type":"password"},{"key":"llm_base_url","label":"记忆 LLM 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"llm_api_key","label":"记忆 LLM 密钥","type":"password"},{"key":"llm_model","label":"记忆 LLM 模型","type":"string"},{"key":"embedding_base_url","label":"Embedding 地址","type":"string","help":"OpenAI 兼容的 v1 根地址。"},{"key":"embedding_api_key","label":"Embedding 密钥","type":"password"},{"key":"embedding_model","label":"Embedding 模型","type":"string"},{"key":"embedding_dimensions","label":"向量维度","type":"integer","default":1024},{"key":"embedding_send_dimensions","label":"发送 dimensions","type":"boolean","default":true,"help":"关闭后不向不兼容的 Embedding 服务发送 dimensions 字段。"}]'
WHERE `id` = 'SYSTEM_Memory_tencentdb';

UPDATE `ai_model_config`
SET `config_json` = JSON_REMOVE(`config_json`, '$.llm_model_id', '$.embedding_model_id')
WHERE `id` = 'Memory_tencentdb';

DELETE FROM `ai_model_config` WHERE `id` = 'Embedding_openai';
DELETE FROM `ai_model_provider` WHERE `id` = 'SYSTEM_Embedding_openai';
```

Append changeSet `202608160100` after `202608141000` in `db.changelog-master.yaml`.

- [ ] **Step 5: Run the migration contract and verify GREEN**

Run:

```bash
mvn -DskipTests=false -Dtest=TencentDbMemoryMigrationContractTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the migration**

```bash
git add src/main/resources/db/changelog/202608160100.sql \
  src/main/resources/db/changelog/202608160100-rollback.sql \
  src/main/resources/db/changelog/db.changelog-master.yaml \
  src/test/java/xiaozhi/modules/model/TencentDbMemoryMigrationContractTest.java
git commit -m "feat: add managed embedding models"
```

### Task 3: Inject safe model-reference options into TencentDB provider metadata

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelCatalogService.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelCatalogServiceTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/controller/ModelControllerConnectionTest.java`

- [ ] **Step 1: Write the failing catalog test**

Test that only enabled, complete OpenAI-compatible LLMs and Embeddings become options and that returned options contain only labels and IDs:

```java
@Test
void enrichesTencentDbFieldsWithoutCredentials() {
    when(modelConfigDao.selectList(any())).thenReturn(List.of(
            model("LLM_GLM", "LLM", 1, "openai", "智谱 GLM", complete("glm-4-flash")),
            model("LLM_Gemini", "LLM", 1, "gemini", "Gemini", complete("gemini")),
            model("Embedding_zhipu", "Embedding", 1, "openai", "智谱 Embedding 3", embedding())));

    ModelProviderDTO provider = providerWithReferenceFields();
    service.enrich(List.of(provider));

    JSONArray fields = JSONUtil.parseArray(provider.getFields());
    assertEquals(List.of(Map.of("label", "智谱 GLM", "value", "LLM_GLM")), options(fields, "llm_model_id"));
    assertEquals(List.of(Map.of("label", "智谱 Embedding 3", "value", "Embedding_zhipu")), options(fields, "embedding_model_id"));
    assertFalse(provider.getFields().contains("secret"));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -DskipTests=false -Dtest=TencentDbMemoryModelCatalogServiceTest test
```

Expected: compilation failure because the service does not exist.

- [ ] **Step 3: Implement the focused catalog service**

Implement a service that queries enabled `LLM` and `Embedding` rows, accepts only `config_json.type=openai`, requires nonblank `base_url`, `api_key`, and `model_name`, sorts by `sort`, and rewrites only the `options` arrays:

```java
@Service
@RequiredArgsConstructor
public class TencentDbMemoryModelCatalogService {
    private final ModelConfigDao modelConfigDao;

    public void enrich(List<ModelProviderDTO> providers) {
        for (ModelProviderDTO provider : providers) {
            if (!"Memory".equalsIgnoreCase(provider.getModelType())
                    || !"tencentdb".equalsIgnoreCase(provider.getProviderCode())) continue;
            JSONArray fields = JSONUtil.parseArray(provider.getFields());
            replaceOptions(fields, "llm_model_id", options("LLM"));
            replaceOptions(fields, "embedding_model_id", options("Embedding"));
            provider.setFields(fields.toString());
        }
    }
}
```

Each option must be exactly `{ "label": modelName, "value": id }`.

- [ ] **Step 4: Route provider-list responses through the catalog**

Add `TencentDbMemoryModelCatalogService` to `ModelController`, then change only `getModelProviderList`:

```java
List<ModelProviderDTO> providers = modelProviderService.getListByModelType(modelType);
memoryModelCatalog.enrich(providers);
return new Result<List<ModelProviderDTO>>().ok(providers);
```

Update `ModelControllerConnectionTest` constructor setup with a mock catalog and verify it is called.

- [ ] **Step 5: Run catalog and controller tests**

Run:

```bash
mvn -DskipTests=false -Dtest=TencentDbMemoryModelCatalogServiceTest,ModelControllerConnectionTest test
```

Expected: PASS.

- [ ] **Step 6: Commit safe reference options**

```bash
git add src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelCatalogService.java \
  src/main/java/xiaozhi/modules/model/controller/ModelController.java \
  src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelCatalogServiceTest.java \
  src/test/java/xiaozhi/modules/model/controller/ModelControllerConnectionTest.java
git commit -m "feat: expose memory model references"
```

### Task 4: Resolve referenced LLM and Embedding settings with legacy fallback

**Files:**
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImpl.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImplTest.java`

- [ ] **Step 1: Write failing reference-resolution tests**

Add tests for successful references, disabled/wrong-type references, incomplete OpenAI config, and legacy fallback:

```java
@Test
void resolvesManagedModelsBeforeLegacyFields() {
    when(models.getModelByIdFromCache("LLM_GLM")).thenReturn(model("LLM", 1,
            config("https://open.bigmodel.cn/api/paas/v4", "glm-key", "glm-4-flash")));
    when(models.getModelByIdFromCache("Embedding_zhipu")).thenReturn(model("Embedding", 1,
            new JSONObject().set("type", "openai")
                    .set("base_url", "https://open.bigmodel.cn/api/paas/v4")
                    .set("api_key", "embedding-key")
                    .set("model_name", "embedding-3")
                    .set("dimensions", 1024)
                    .set("send_dimensions", true)));

    TencentDbMemoryModelSettings settings = service.parseModelSettings(new JSONObject()
            .set("type", "tencentdb")
            .set("llm_model_id", "LLM_GLM")
            .set("embedding_model_id", "Embedding_zhipu"));

    assertEquals("glm-4-flash", settings.llmModel());
    assertEquals("embedding-3", settings.embeddingModel());
    assertEquals(1024, settings.embeddingDimensions());
}
```

Keep the existing legacy raw-field test and rename it to state that it is a fallback.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
mvn -DskipTests=false -Dtest=TencentDbMemoryModelSettingsServiceImplTest test
```

Expected: FAIL because reference IDs are ignored.

- [ ] **Step 3: Implement reference-first parsing**

Add helpers with explicit type and enabled-state validation:

```java
private ModelConfigEntity referenced(JSONObject memory, String key, String modelType) {
    String id = StringUtils.trimToNull(memory.getStr(key));
    if (id == null) return null;
    ModelConfigEntity model = modelConfigService.getModelByIdFromCache(id);
    if (model == null || !modelType.equalsIgnoreCase(model.getModelType())
            || model.getIsEnabled() == null || model.getIsEnabled() != 1) {
        throw new IllegalStateException("TencentDB 记忆引用的" + modelType + "模型不可用");
    }
    JSONObject config = model.getConfigJson();
    if (config == null || !"openai".equalsIgnoreCase(config.getStr("type"))) {
        throw new IllegalStateException("TencentDB 记忆仅支持 OpenAI 兼容的" + modelType + "模型");
    }
    return model;
}
```

When both IDs are nonblank, parse referenced configs. When both IDs are blank, call the existing legacy parser. Reject a half-configured pair instead of mixing one reference with one legacy model.

- [ ] **Step 4: Run settings and proxy tests**

Run:

```bash
mvn -DskipTests=false -Dtest=TencentDbMemoryModelSettingsServiceImplTest,TencentDbMemoryModelProxyServiceImplTest test
```

Expected: PASS.

- [ ] **Step 5: Commit reference resolution**

```bash
git add src/main/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImpl.java \
  src/test/java/xiaozhi/modules/model/tencentdb/TencentDbMemoryModelSettingsServiceImplTest.java
git commit -m "feat: resolve memory model references"
```

### Task 5: Add direct Embedding connection testing

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/model/tencentdb/OpenAiEmbeddingConnectionTester.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/tencentdb/OpenAiEmbeddingConnectionTesterTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java`

- [ ] **Step 1: Write the failing Embedding tester test**

Use a fake upstream client response and assert the request uses `/embeddings`, the configured model, and dimensions:

```java
@Test
void testsOpenAiCompatibleEmbeddingWithConfiguredDimensions() throws Exception {
    when(upstream.post(any(), eq("secret"), any(), any())).thenReturn(response(200,
            "{\"data\":[{\"embedding\":[0.1,0.2]}]}"));

    CompanionModelTestVO result = tester.test(new JSONObject()
            .set("type", "openai")
            .set("base_url", "https://embedding.example/v1")
            .set("api_key", "secret")
            .set("model_name", "embedding-3")
            .set("dimensions", 1024)
            .set("send_dimensions", true));

    assertTrue(result.isSuccess());
    verify(upstream).post(eq(URI.create("https://embedding.example/v1/embeddings")), eq("secret"),
            body.capture(), any());
    assertEquals(1024, JSONUtil.parseObj(new String(body.getValue(), UTF_8)).getInt("dimensions"));
}
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
mvn -DskipTests=false -Dtest=OpenAiEmbeddingConnectionTesterTest test
```

Expected: compilation failure because the tester does not exist.

- [ ] **Step 3: Implement the tester**

Create a focused service that validates fields, sends `input: "连接测试"`, checks a 2xx response and nonempty `data[0].embedding`, closes the response body, and returns Chinese failure messages without upstream response bodies.

```java
JSONObject payload = new JSONObject()
        .set("model", modelName)
        .set("input", "连接测试");
if (sendDimensions) payload.set("dimensions", dimensions);
```

- [ ] **Step 4: Route `Embedding/openai` tests**

Inject the tester into `ModelConnectionTestServiceImpl` before the conversation-model branch:

```java
if ("Embedding".equalsIgnoreCase(modelType) && "openai".equalsIgnoreCase(providerCode)) {
    return embeddingTester.test(mergedRuntime(modelType, id, body));
}
```

Add service tests for saved secret preservation and unsupported Embedding providers.

- [ ] **Step 5: Run connection tests**

Run:

```bash
mvn -DskipTests=false -Dtest=OpenAiEmbeddingConnectionTesterTest,ModelConnectionTestServiceImplTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Embedding testing**

```bash
git add src/main/java/xiaozhi/modules/model/tencentdb/OpenAiEmbeddingConnectionTester.java \
  src/main/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImpl.java \
  src/test/java/xiaozhi/modules/model/tencentdb/OpenAiEmbeddingConnectionTesterTest.java \
  src/test/java/xiaozhi/modules/model/service/impl/ModelConnectionTestServiceImplTest.java
git commit -m "feat: test embedding connections"
```

### Task 6: Add Embedding and model references to the React console

**Files:**
- Modify: `server/main/companion-console/src/api/xiaozhiModels.ts`
- Modify: `server/main/companion-console/src/api/xiaozhiModels.test.ts`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`
- Modify: `server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx`
- Modify: `server/main/companion-console/src/pages/models/modelEditorMetadata.ts`
- Modify: `server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts`

- [ ] **Step 1: Write failing API and page tests**

Add `Embedding` to the expected type parser and render tests. Replace the old raw TencentDB credential test with reference selects:

```tsx
it('renders TencentDB model references as selects', () => {
  const fields: ModelProviderField[] = [
    { key: 'llm_model_id', label: '记忆 LLM', type: 'string', options: [{ label: '智谱 GLM', value: 'LLM_GLM' }] },
    { key: 'embedding_model_id', label: 'Embedding 模型', type: 'string', options: [{ label: '智谱 Embedding 3', value: 'Embedding_zhipu' }] },
  ]
  render(<Form><ModelFieldEditor modelType="Memory" fields={fields} /></Form>)
  expect(screen.getByRole('combobox', { name: '记忆 LLM' })).toBeInTheDocument()
  expect(screen.getByRole('combobox', { name: 'Embedding 模型' })).toBeInTheDocument()
  expect(screen.queryByLabelText('记忆 LLM 密钥')).not.toBeInTheDocument()
})
```

Add a page test that opens the `Embedding 模型 Embedding` tab and sees an OpenAI-compatible provider. Add another test that edits TencentDB and saves `llm_model_id` plus `embedding_model_id` without any referenced credential.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
npm test -- src/api/xiaozhiModels.test.ts src/pages/models/ModelFieldEditor.test.tsx src/pages/models/ModelManagementPage.test.tsx src/pages/models/modelEditorMetadata.test.ts
```

Expected: FAIL because `Embedding` is not a model type and no tab exists.

- [ ] **Step 3: Implement the minimal React changes**

Change the model type tuple and label map:

```ts
export const modelTypes = ['LLM', 'VLLM', 'TTS', 'ASR', 'VAD', 'Memory', 'Embedding'] as const
```

```ts
const typeLabels: Record<ModelType, string> = {
  // existing labels
  Embedding: 'Embedding 模型 Embedding',
}
```

Enable connection testing for Embedding/openai:

```ts
return ((modelType === 'LLM' || modelType === 'VLLM' || modelType === 'Embedding') && provider === 'openai')
  || (modelType === 'Memory' && provider === 'tencentdb')
```

No special secret fetching or client-side model lookup is added. The existing provider parser and `ModelFieldEditor` consume the safe injected `options` arrays.

- [ ] **Step 4: Run React tests and build**

Run:

```bash
npm test -- src/api/xiaozhiModels.test.ts src/pages/models/ModelFieldEditor.test.tsx src/pages/models/ModelManagementPage.test.tsx src/pages/models/modelEditorMetadata.test.ts
npm run build
```

Expected: tests and build PASS.

- [ ] **Step 5: Commit React support**

```bash
git add src/api/xiaozhiModels.ts src/api/xiaozhiModels.test.ts \
  src/pages/models/ModelManagementPage.tsx src/pages/models/ModelManagementPage.test.tsx \
  src/pages/models/ModelFieldEditor.test.tsx \
  src/pages/models/modelEditorMetadata.ts src/pages/models/modelEditorMetadata.test.ts
git commit -m "feat(console): manage embedding models"
```

### Task 7: Add Embedding and reference selects to the Vue manager

**Files:**
- Modify: `server/main/manager-web/src/views/ModelConfig.vue`
- Modify: `server/main/manager-web/src/views/ProviderManagement.vue`
- Modify: `server/main/manager-web/src/components/AddModelDialog.vue`
- Modify: `server/main/manager-web/src/components/ModelEditDialog.vue`
- Modify: `server/main/manager-web/src/components/modelFieldUtils.mjs`
- Modify: `server/main/manager-web/tests/modelFieldUtils.test.mjs`
- Create: `server/main/manager-web/tests/embeddingModelManagement.test.mjs`
- Modify: `server/main/manager-web/src/i18n/zh_CN.js`
- Modify: `server/main/manager-web/src/i18n/en.js`
- Modify: `server/main/manager-web/src/i18n/vi.js`

- [ ] **Step 1: Write failing field and navigation tests**

Extend the Node test to prove label/value option objects stay select options. Add a source contract for the Embedding menu and translations:

```js
test('model management exposes Embedding navigation', () => {
  const page = readFileSync(new URL('../src/views/ModelConfig.vue', import.meta.url), 'utf8')
  assert.match(page, /index="embedding"/)
  assert.match(page, /modelConfig\.embedding/)
})
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
npm run test:unit
```

Expected: FAIL because the Embedding navigation is absent.

- [ ] **Step 3: Implement Vue model type support**

Add the menu item after Memory:

```vue
<el-menu-item index="embedding">
  <span class="menu-text">{{ $t("modelConfig.embedding") }}</span>
</el-menu-item>
```

Add `Embedding` to `ProviderManagement.vue` and the three translation files. Keep `normalizeModelField` mapping any nonempty options array to `select`; ensure both add and edit dialogs render `el-select` and boolean fields render `el-switch`.

- [ ] **Step 4: Run Vue tests and build**

Run:

```bash
npm run test:unit
npm run build
```

Expected: tests and build PASS.

- [ ] **Step 5: Commit Vue support**

```bash
git add src/views/ModelConfig.vue src/views/ProviderManagement.vue \
  src/components/AddModelDialog.vue src/components/ModelEditDialog.vue src/components/modelFieldUtils.mjs \
  tests/modelFieldUtils.test.mjs tests/embeddingModelManagement.test.mjs \
  src/i18n/zh_CN.js src/i18n/en.js src/i18n/vi.js
git commit -m "feat(web): manage embedding models"
```

### Task 8: Add safe local MemoryCore configuration automation

**Files:**
- Create: `server/main/xiaozhi-server/deploy/tencentdb-memory/configure-local.sh`
- Modify: `.gitignore`
- Modify: `server/main/xiaozhi-server/docs/tencentdb-agent-memory.md`
- Modify: `server/main/xiaozhi-server/tests/test_tencentdb_memory_operations_docs.py`
- Modify: `server/main/xiaozhi-server/tests/test_tencentdb_memory_local_config.py`

- [ ] **Step 1: Write failing documentation and ignore tests**

Add assertions:

```python
def test_local_memory_environment_is_ignored_and_configurable():
    root = Path(__file__).parents[4]
    assert "server/main/xiaozhi-server/.env.tencentdb-memory" in (root / ".gitignore").read_text()
    script = (ROOT / "deploy/tencentdb-memory/configure-local.sh").read_text()
    assert "TENCENTDB_MEMORY_CORE_KEY" in script
    assert "server.secret" in script
    assert "docker compose" in script
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
python -m pytest -q tests/test_tencentdb_memory_operations_docs.py tests/test_tencentdb_memory_local_config.py
```

Expected: FAIL because the script and ignore entry do not exist.

- [ ] **Step 3: Implement the local configuration script**

The script must use `set -euo pipefail`, configurable container/database defaults, `openssl rand -hex 32`, `umask 077`, and never echo secret values. If `.env.tencentdb-memory` already contains a nonblank core key, preserve it so rerunning the script does not invalidate the saved backend credential:

```bash
memory_mysql_container="${MEMORY_MYSQL_CONTAINER:-ai-plush-companion-mysql}"
memory_mysql_password="${MEMORY_MYSQL_ROOT_PASSWORD:-123456}"
memory_mysql_database="${MEMORY_MYSQL_DATABASE:-xiaozhi_esp32_server}"
memory_proxy_key="$(docker exec "$memory_mysql_container" mysql -uroot -p"$memory_mysql_password" \
  -D "$memory_mysql_database" -N -B -e \
  "SELECT param_value FROM sys_params WHERE param_code='server.secret' LIMIT 1")"
if [[ -f .env.tencentdb-memory ]]; then
  memory_core_key="$(sed -n 's/^TENCENTDB_MEMORY_CORE_KEY=//p' .env.tencentdb-memory | head -n 1)"
fi
memory_core_key="${memory_core_key:-$(openssl rand -hex 32)}"
```

Write `.env.tencentdb-memory` with mode 600, set proxy URL to `http://host.docker.internal:8002/xiaozhi/internal/tencentdb-memory-model/v1`, then run:

```bash
docker compose --env-file .env.tencentdb-memory \
  -f docker-compose.yml -f docker-compose.tencentdb-memory.dev.yml \
  up -d tencentdb-memory-core
```

Add the exact ignored path to the repository `.gitignore` and document current local versus full-server addresses.

- [ ] **Step 4: Run Python tests and shell syntax check**

Run:

```bash
bash -n deploy/tencentdb-memory/configure-local.sh
python -m pytest -q tests/test_tencentdb_memory_operations_docs.py tests/test_tencentdb_memory_local_config.py tests/test_tencentdb_memory_compose.py
```

Expected: PASS.

- [ ] **Step 5: Commit local automation**

```bash
git add ../../../../.gitignore deploy/tencentdb-memory/configure-local.sh \
  docs/tencentdb-agent-memory.md tests/test_tencentdb_memory_operations_docs.py \
  tests/test_tencentdb_memory_local_config.py
git commit -m "feat: configure local memory core"
```

### Task 9: Run the complete implementation verification

**Files:**
- No new production files

- [ ] **Step 1: Run manager-api tests**

Run:

```bash
cd server/main/manager-api
mvn clean test -DskipTests=false
```

Expected: all Java tests PASS.

- [ ] **Step 2: Run React tests and build**

Run:

```bash
cd ../companion-console
npm test
npm run build
```

Expected: all Vitest tests and build PASS.

- [ ] **Step 3: Run Vue tests and build**

Run:

```bash
cd ../manager-web
npm run test:unit
npm run build
```

Expected: Node tests and production build PASS.

- [ ] **Step 4: Run TencentDB Python tests**

Run the established project Python environment against:

```bash
python -m pytest -q \
  tests/test_tencentdb_memory_contract.py \
  tests/test_tencentdb_memory_provider_management.py \
  tests/test_tencentdb_memory_client.py \
  tests/test_tencentdb_memory_local_config.py \
  tests/test_tencentdb_memory_provider_capture.py \
  tests/test_tencentdb_memory_operations_docs.py \
  tests/integration/test_tencentdb_memory_provider_flow.py \
  tests/test_tencentdb_memory_compose.py \
  tests/test_tencentdb_memory_gateway_config.py \
  tests/test_tencentdb_memory_provider_recall.py \
  tests/test_memory_namespace.py \
  tests/test_external_memory_clear.py \
  tests/test_companion_memory_management.py \
  tests/test_memory_handler.py
```

Expected: all selected tests PASS.

- [ ] **Step 5: Verify Compose rendering**

Run with generated nonproduction values:

```bash
TENCENTDB_MEMORY_CORE_KEY=test-core \
TENCENTDB_MEMORY_MODEL_PROXY_KEY=test-proxy \
docker compose -f docker-compose.yml -f docker-compose.tencentdb-memory.dev.yml config >/dev/null
```

Expected: exit 0.

### Task 10: Merge the implementation without consuming main's existing changes

**Files:**
- Main worktree and feature worktree only

- [ ] **Step 1: Identify only overlapping dirty files**

Run from main:

```bash
git status --short
git diff --name-only main..codex/tencentdb-memory-model-references
```

The expected intersection with the current main worktree is limited to the model metadata and dialog files below. Re-run both commands and remove any path that is no longer dirty before stashing.

- [ ] **Step 2: Stash only overlapping main changes**

Run with the explicit intersection paths:

```bash
git stash push -m "preserve main changes before memory model merge" -- \
  server/main/companion-console/src/api/xiaozhiModels.ts \
  server/main/companion-console/src/pages/models/modelEditorMetadata.ts \
  server/main/companion-console/src/pages/models/modelEditorMetadata.test.ts \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml \
  server/main/manager-web/src/components/AddModelDialog.vue \
  server/main/manager-web/src/components/ModelEditDialog.vue
```

Expected: unrelated dirty files remain visible.

- [ ] **Step 3: Fast-forward merge the feature branch**

Run:

```bash
git merge --ff-only codex/tencentdb-memory-model-references
```

Expected: main advances to the feature branch tip.

- [ ] **Step 4: Restore main changes and resolve only genuine overlaps**

Run:

```bash
git stash pop
```

Expected: existing main edits return. If identical changes were implemented in the feature branch, keep the merged implementation and preserve any additional user edits.

- [ ] **Step 5: Re-run focused tests on merged main**

Run the Java TencentDB tests, React model tests, Vue unit tests, and Python TencentDB tests from Task 9.

Expected: PASS, except any pre-existing unrelated failures must be reported without modifying their source.

### Task 11: Apply local configuration and validate the real stack

**Files:**
- Generated and ignored: `server/main/xiaozhi-server/.env.tencentdb-memory`
- Runtime database rows: `Embedding_openai`, `Memory_tencentdb`
- Docker service and volume: `tencentdb-memory-core`, `tencentdb_memory_data`

- [ ] **Step 1: Restart manager-api so Liquibase applies the new migration**

Use the existing local container's normal restart command, then verify:

```sql
SELECT id, model_type, model_code, is_enabled
FROM ai_model_config
WHERE id IN ('Embedding_openai', 'Memory_tencentdb');
```

Expected: both rows exist.

- [ ] **Step 2: Reuse the existing GLM credential without printing it**

Execute one SQL update inside the MySQL container that copies only the stored key and writes public Embedding fields:

```sql
UPDATE ai_model_config embedding
JOIN ai_model_config llm ON llm.id = 'LLM_ChatGLMLLM'
SET embedding.model_name = '智谱 Embedding 3',
    embedding.is_enabled = 1,
    embedding.config_json = JSON_OBJECT(
        'type', 'openai',
        'base_url', 'https://open.bigmodel.cn/api/paas/v4',
        'api_key', JSON_UNQUOTE(JSON_EXTRACT(llm.config_json, '$.api_key')),
        'model_name', 'embedding-3',
        'dimensions', 1024,
        'send_dimensions', TRUE
    )
WHERE embedding.id = 'Embedding_openai';
```

Then clear Redis model cache keys for `Embedding_openai`.

- [ ] **Step 3: Generate the local MemoryCore environment and start the service**

Run:

```bash
cd server/main/xiaozhi-server
bash deploy/tencentdb-memory/configure-local.sh
```

Expected: the MemoryCore container becomes healthy and `http://127.0.0.1:8420/health` returns 2xx.

- [ ] **Step 4: Save TencentDB model references in the backend**

Use the logged-in Chrome admin UI to edit `Memory_tencentdb`. Set MemoryCore URL to `http://host.docker.internal:8420`, paste the generated MemoryCore key from the ignored environment file without displaying it, select `LLM_ChatGLMLLM`, select `Embedding_openai`, enable the model, run connection testing, and save.

Expected: the UI reports MemoryCore, memory LLM, and Embedding are available.

- [ ] **Step 5: Switch the test companion role to TencentDB Memory**

Use the existing profile model settings to choose `Memory_tencentdb` for the test role and save.

Expected: the effective model summary shows TencentDB Agent Memory.

- [ ] **Step 6: Run real persistence and recall smoke tests**

Run:

```bash
set -a
. ./.env.tencentdb-memory
set +a
MEMORY_CORE_URL=http://127.0.0.1:8420 \
MEMORY_CORE_API_KEY="$TENCENTDB_MEMORY_CORE_KEY" \
bash deploy/tencentdb-memory/smoke-test.sh
```

Restart only MemoryCore, then run `--verify-existing`. Confirm L0 survives, L1 appears, and no secret or memory text is printed.

- [ ] **Step 7: Clean up the feature worktree and branch**

After merged-main verification succeeds:

```bash
git worktree remove .worktrees/tencentdb-memory-model-references
git worktree prune
git branch -d codex/tencentdb-memory-model-references
```

Expected: only the main worktree remains. Do not push unless the user asks.
