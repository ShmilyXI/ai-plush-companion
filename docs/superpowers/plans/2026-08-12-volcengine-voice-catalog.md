# Volcengine Voice Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the editable timbre catalog with a read-only Volcengine `ListSpeakers` browser, consolidate Volcengine TTS 1.0 and 2.0 into one model, and limit TTS management to Edge, Volcengine, and Alibaba Bailian.

**Architecture:** Keep `/ttsVoice` and `ai_tts_voice` intact for role configuration and compatibility. Add a dedicated backend Volcengine catalog module that reads the unmasked `TTS_HuoshanDoubleStreamTTS` credentials, signs `ListSpeakers` requests, normalizes the response, and exposes a read-only endpoint to the console. Filter model-management queries at the backend boundary, while a Liquibase migration consolidates old 2.0 references and updates the visible Volcengine provider metadata.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Jackson, Java `HttpClient`, Liquibase, JUnit 5, Mockito, React, TypeScript, Ant Design, Vitest, Testing Library, Python `unittest`, websockets.

---

### Task 1: Add the Volcengine consolidation migration

**Files:**
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/entity/CompanionSchemaContractTest.java`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608121200.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608121200-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Write the failing schema contract test**

Add a test that reads the three migration resources and asserts the forward migration contains all compatibility updates and the provider field definitions:

```java
@Test
void volcengineTtsMigrationConsolidatesV2ReferencesAndRegistersRollback() throws Exception {
    String sql = resource("/db/changelog/202608121200.sql");
    String rollbackSql = resource("/db/changelog/202608121200-rollback.sql");
    String master = resource("/db/changelog/db.changelog-master.yaml");

    assertTrue(sql.contains("UPDATE `ai_agent` SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("UPDATE `ai_agent_template` SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("UPDATE `ai_voice_clone` SET `model_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("UPDATE `ai_tts_voice` SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("UPDATE `ai_companion_profile_model` SET `resource_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("UPDATE `ai_companion_global_model_credential` SET `global_model_id` = 'TTS_HuoshanDoubleStreamTTS'"));
    assertTrue(sql.contains("'seed-tts-1.0'"));
    assertTrue(sql.contains("'seed-tts-2.0'"));
    assertTrue(sql.contains("\"access_key_id\""));
    assertTrue(sql.contains("\"secret_access_key\""));
    assertTrue(sql.contains("WHERE `id` = 'TTS_HSDSTTS_V2'"));
    assertTrue(rollbackSql.contains("TTS_HSDSTTS_V2"));
    assertTrue(master.contains("id: 202608121200"));
    assertTrue(master.contains("path: classpath:db/changelog/202608121200.sql"));
    assertTrue(master.contains("path: classpath:db/changelog/202608121200-rollback.sql"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=CompanionSchemaContractTest#volcengineTtsMigrationConsolidatesV2ReferencesAndRegistersRollback test
```

Expected: FAIL because `202608121200.sql` is missing.

- [ ] **Step 3: Add the forward migration**

Create an idempotent migration that first consolidates references where the corresponding tables exist in the current schema, then updates the unified model and provider. Preserve every old row; only disable the old visible model.

```sql
UPDATE `ai_agent`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_agent_template`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_voice_clone`
SET `model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_tts_voice`
SET `tts_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `tts_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_companion_profile_model`
SET `resource_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `resource_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_companion_global_model_credential`
SET `global_model_id` = 'TTS_HuoshanDoubleStreamTTS'
WHERE `global_model_id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET `is_enabled` = 0,
    `is_default` = 0
WHERE `id` = 'TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET `model_name` = '火山引擎语音合成',
    `config_json` = JSON_SET(
        COALESCE(`config_json`, JSON_OBJECT()),
        '$.type', 'huoshan_double_stream',
        '$.ws_url', 'wss://openspeech.bytedance.com/api/v3/tts/bidirection',
        '$.resource_id', CASE
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')) = 'seed-tts-2.0'
                THEN 'seed-tts-2.0'
            ELSE 'seed-tts-1.0'
        END,
        '$.access_key_id', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.access_key_id')), ''),
        '$.secret_access_key', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.secret_access_key')), '')
    ),
    `doc_link` = 'https://docs.volcengine.com/docs/6561/2160690?lang=zh'
WHERE `id` = 'TTS_HuoshanDoubleStreamTTS';

UPDATE `ai_model_provider`
SET `name` = '火山引擎',
    `fields` = '[{"key":"ws_url","label":"WebSocket地址","type":"string","default":"wss://openspeech.bytedance.com/api/v3/tts/bidirection"},{"key":"appid","label":"应用ID","type":"string"},{"key":"access_token","label":"访问令牌","type":"password"},{"key":"resource_id","label":"模型版本","type":"string","options":[{"label":"语音合成 1.0","value":"seed-tts-1.0"},{"label":"语音合成 2.0","value":"seed-tts-2.0"}],"default":"seed-tts-1.0"},{"key":"access_key_id","label":"Access Key ID","type":"string"},{"key":"secret_access_key","label":"Secret Access Key","type":"password"},{"key":"speaker","label":"默认音色","type":"string"},{"key":"enable_ws_reuse","label":"是否开启链接复用","type":"boolean","default":true},{"key":"audio_params","label":"音频参数","type":"dict","default":{}},{"key":"additions","label":"附加参数","type":"dict","default":{}},{"key":"mix_speaker","label":"混音配置","type":"dict","default":{}}]'
WHERE `id` = 'SYSTEM_TTS_HSDSTTS';
```

If either companion table is absent in the schema represented by the migrations, replace its direct `UPDATE` with a MySQL `information_schema.tables` guarded prepared statement so the migration remains deployable while retaining the contract text in a comment.

- [ ] **Step 4: Add the rollback and register the changeSet**

The rollback restores provider metadata, removes the two catalog credentials from the unified model JSON, and re-enables `TTS_HSDSTTS_V2`. It must not move references back because the migration cannot know which unified references originally came from 2.0.

Append to `db.changelog-master.yaml`:

```yaml
  - changeSet:
      id: 202608121200
      author: Codex
      changes:
        - sqlFile:
            encoding: utf8
            path: classpath:db/changelog/202608121200.sql
      rollback:
        - sqlFile:
            encoding: utf8
            path: classpath:db/changelog/202608121200-rollback.sql
```

- [ ] **Step 5: Run the schema contract test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 6: Commit the migration**

```bash
git add server/main/manager-api/src/test/java/xiaozhi/modules/companion/entity/CompanionSchemaContractTest.java \
  server/main/manager-api/src/main/resources/db/changelog/202608121200.sql \
  server/main/manager-api/src/main/resources/db/changelog/202608121200-rollback.sql \
  server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat: consolidate volcengine tts models"
```

### Task 2: Filter TTS model-management queries at the backend boundary

**Files:**
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/service/ModelConfigServiceImplTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/model/service/ModelProviderServiceImplTest.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelProviderServiceImpl.java`

- [ ] **Step 1: Write failing model whitelist tests**

Build each service with mocked DAOs and capture the `QueryWrapper` passed to MyBatis-Plus. Verify `TTS` queries add the visible IDs or provider codes, while `LLM` queries do not receive a TTS restriction.

```java
private static final Set<String> VISIBLE_TTS_MODEL_IDS = Set.of(
        "TTS_EdgeTTS", "TTS_HuoshanDoubleStreamTTS", "TTS_AliBLStreamTTS");

@Test
void ttsPageOnlyReturnsSupportedManagementModels() {
    when(modelConfigDao.selectPage(any(), any())).thenAnswer(invocation -> {
        QueryWrapper<ModelConfigEntity> wrapper = invocation.getArgument(1);
        String segment = wrapper.getExpression().getNormal().toString();
        assertTrue(segment.contains("id"));
        Page<ModelConfigEntity> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        return page;
    });

    service.getPageList("TTS", null, "1", "10");

    verify(modelConfigDao).selectPage(any(), any());
}
```

For provider filtering, assert the returned DTO codes equal:

```java
assertEquals(List.of("edge", "huoshan_double_stream", "alibl_stream"),
        service.getListByModelType("TTS").stream().map(ModelProviderDTO::getProviderCode).toList());
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=ModelConfigServiceImplTest,ModelProviderServiceImplTest test
```

Expected: FAIL because unsupported TTS rows are not filtered.

- [ ] **Step 3: Add the query restrictions**

Add constants and conditional wrappers in the two services:

```java
private static final List<String> VISIBLE_TTS_MODEL_IDS = List.of(
        "TTS_EdgeTTS",
        "TTS_HuoshanDoubleStreamTTS",
        "TTS_AliBLStreamTTS");

private QueryWrapper<ModelConfigEntity> managementQuery(String modelType, String modelName) {
    QueryWrapper<ModelConfigEntity> wrapper = new QueryWrapper<ModelConfigEntity>()
            .eq("model_type", modelType)
            .like(StringUtils.isNotBlank(modelName), "model_name", modelName);
    if ("TTS".equalsIgnoreCase(modelType)) {
        wrapper.in("id", VISIBLE_TTS_MODEL_IDS);
    }
    return wrapper;
}
```

Use `managementQuery` in both `getPageList` and `getModelCodeList`, so dropdowns that represent the management catalog cannot expose hidden TTS models. Do not apply it to `selectById`, `getModelsByType`, or runtime configuration reads.

For providers:

```java
private static final List<String> VISIBLE_TTS_PROVIDER_CODES = List.of(
        "edge", "huoshan_double_stream", "alibl_stream");

if ("TTS".equalsIgnoreCase(modelType)) {
    queryWrapper.in("provider_code", VISIBLE_TTS_PROVIDER_CODES);
}
```

- [ ] **Step 4: Run the service tests**

Run the command from Step 2.

Expected: PASS, including the non-TTS regression cases.

- [ ] **Step 5: Commit the whitelist behavior**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelProviderServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/service/ModelConfigServiceImplTest.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/model/service/ModelProviderServiceImplTest.java
git commit -m "feat: limit managed tts providers"
```

### Task 3: Implement deterministic Volcengine request signing

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineRequestSigner.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/SignedVolcengineRequest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/volcengine/voice/VolcengineRequestSignerTest.java`

- [ ] **Step 1: Write a failing signing-vector test**

Use a fixed `Clock`, fixed JSON body, known credentials, and exact expected headers. The test must assert the canonical request hash and Authorization value, not merely that they are non-empty.

```java
@Test
void signsListSpeakersWithVolcengineHmacSha256() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-12T03:04:05Z"), ZoneOffset.UTC);
    VolcengineRequestSigner signer = new VolcengineRequestSigner(clock);

    SignedVolcengineRequest request = signer.sign(
            URI.create("https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20"),
            "{\"ResourceIDs\":[\"seed-tts-1.0\"],\"Page\":1,\"Limit\":20}",
            "AKLT_TEST", "secret-test", "cn-beijing", "speech_saas_prod");

    assertEquals("20260812T030405Z", request.headers().get("X-Date"));
    assertEquals(64, request.headers().get("X-Content-Sha256").length());
    assertEquals("application/json", request.headers().get("Content-Type"));
    assertTrue(request.headers().get("Authorization").startsWith(
            "HMAC-SHA256 Credential=AKLT_TEST/20260812/cn-beijing/speech_saas_prod/request, "));
    assertEquals(EXPECTED_AUTHORIZATION, request.headers().get("Authorization"));
}
```

Calculate `EXPECTED_AUTHORIZATION` once from the documented Volcengine V4 algorithm and keep it as a literal regression vector.

- [ ] **Step 2: Run the signing test to verify it fails**

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=VolcengineRequestSignerTest test
```

Expected: FAIL because the signer does not exist.

- [ ] **Step 3: Implement the signer**

Use UTF-8 everywhere and lowercase hexadecimal SHA-256 output. Canonicalize the request as:

```text
POST
/
Action=ListSpeakers&Version=2025-05-20
content-type:application/json
host:open.volcengineapi.com
x-content-sha256:<payload hash>
x-date:<UTC timestamp>

content-type;host;x-content-sha256;x-date
<payload hash>
```

Derive the signature with:

```java
byte[] kDate = hmac(("HMAC-SHA256" + secretAccessKey).getBytes(UTF_8), shortDate);
byte[] kRegion = hmac(kDate, region);
byte[] kService = hmac(kRegion, service);
byte[] kSigning = hmac(kService, "request");
String signature = hex(hmac(kSigning, stringToSign));
```

Return an immutable carrier:

```java
public record SignedVolcengineRequest(URI uri, String body, Map<String, String> headers) {}
```

- [ ] **Step 4: Run the signing test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit the signer**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice \
  server/main/manager-api/src/test/java/xiaozhi/modules/volcengine/voice/VolcengineRequestSignerTest.java
git commit -m "feat: sign volcengine openapi requests"
```

### Task 4: Add the read-only Volcengine voice catalog backend

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogClient.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogConfiguration.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogServiceTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/volcengine/voice/VolcengineVoiceCatalogControllerTest.java`

- [ ] **Step 1: Write failing service tests**

Cover both resource IDs, response normalization, `VoiceTypes` exact lookup, local name filtering, missing credentials, non-2xx responses, Volcengine business errors, malformed JSON, and absent `TrialURL`.

Use a stable project DTO:

```java
public record VolcengineVoiceDTO(
        String id,
        String name,
        String voiceType,
        String gender,
        String age,
        String languages,
        List<String> tags,
        String description,
        String trialUrl) {}
```

Expected mapping assertions:

```java
assertEquals("zh_female_test_bigtts", voice.id());
assertEquals("测试女声", voice.name());
assertEquals("女", voice.gender());
assertEquals("青年", voice.age());
assertEquals("中文、英文", voice.languages());
assertEquals(List.of("通用", "温柔", "多情感"), voice.tags());
assertEquals("https://example.com/trial.mp3", voice.trialUrl());
```

- [ ] **Step 2: Run the service tests to verify they fail**

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=VolcengineVoiceCatalogServiceTest,VolcengineVoiceCatalogControllerTest test
```

Expected: FAIL because the catalog classes do not exist.

- [ ] **Step 3: Add an injectable HTTP client and configuration**

Expose the JDK client as a bean:

```java
@Configuration
public class VolcengineVoiceCatalogConfiguration {
    @Bean
    HttpClient volcengineVoiceHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    Clock volcengineVoiceClock() {
        return Clock.systemUTC();
    }
}
```

The client accepts the signed request, uses a 20-second request timeout, and returns the raw status/body to the service. Never log the Authorization header or request credentials.

- [ ] **Step 4: Implement the catalog service**

Read the unmasked configuration through `ModelConfigService.getModelByIdFromCache("TTS_HuoshanDoubleStreamTTS")`. Validate `resourceId` against exactly:

```java
private static final Set<String> RESOURCE_IDS = Set.of("seed-tts-1.0", "seed-tts-2.0");
```

Build the body with Jackson so the signer and HTTP client use identical bytes:

```java
ObjectNode body = objectMapper.createObjectNode();
body.putArray("ResourceIDs").add(resourceId);
body.put("Page", page);
body.put("Limit", limit);
if (StringUtils.isNotBlank(voiceType)) {
    body.putArray("VoiceTypes").add(voiceType.trim());
}
```

Read `access_key_id` and `secret_access_key` from the config. Throw `RenException("请先在模型管理中配置火山引擎 Access Key ID 和 Secret Access Key")` when either is blank or masked. Call:

```java
URI.create("https://open.volcengineapi.com/?Action=ListSpeakers&Version=2025-05-20")
```

Parse the documented response envelope defensively. Treat a non-empty response metadata error code as failure. Map `Speakers`; merge `NormalLabels` and `SpecialLabels` with insertion-order de-duplication. Apply a case-insensitive name filter after normalization and return `new PageData<>(voices, reportedTotalOrFilteredSize)`.

- [ ] **Step 5: Add the controller**

Expose only a `GET` endpoint:

```java
@RestController
@RequestMapping("/volcengine/voices")
@RequiredArgsConstructor
public class VolcengineVoiceCatalogController {
    private final VolcengineVoiceCatalogService service;

    @GetMapping
    @RequiresPermissions("sys:role:superAdmin")
    public Result<PageData<VolcengineVoiceDTO>> list(
            @RequestParam String resourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String voiceType) {
        return new Result<PageData<VolcengineVoiceDTO>>()
                .ok(service.list(resourceId, page, limit, name, voiceType));
    }
}
```

Reject page values below 1 and limit values outside 1 through 100 with `RenException(ErrorCode.PARAMS_GET_ERROR)`.

- [ ] **Step 6: Run the backend catalog tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 7: Run all touched backend tests**

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=CompanionSchemaContractTest,ModelConfigServiceImplTest,ModelProviderServiceImplTest,VolcengineRequestSignerTest,VolcengineVoiceCatalogServiceTest,VolcengineVoiceCatalogControllerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit the voice catalog backend**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/volcengine/voice \
  server/main/manager-api/src/test/java/xiaozhi/modules/volcengine/voice
git commit -m "feat: expose volcengine voice catalog"
```

### Task 5: Replace the editable timbre API with a typed Volcengine catalog API

**Files:**
- Create: `server/main/companion-console/src/api/volcengineVoices.ts`
- Create: `server/main/companion-console/src/api/volcengineVoices.test.ts`

- [ ] **Step 1: Write failing API parsing tests**

Test a valid `PageData` response, an invalid voice row, and a business error. The parsed frontend type is:

```typescript
export interface VolcengineVoice {
  id: string
  name: string
  voiceType: string
  gender: string | null
  age: string | null
  languages: string | null
  tags: string[]
  description: string | null
  trialUrl: string | null
}
```

The request parameters are:

```typescript
export interface ListVolcengineVoicesParams {
  resourceId: 'seed-tts-1.0' | 'seed-tts-2.0'
  page: number
  limit: number
  name?: string
  voiceType?: string
}
```

- [ ] **Step 2: Run the API test to verify it fails**

```bash
cd server/main/companion-console
npm test -- src/api/volcengineVoices.test.ts
```

Expected: FAIL because the module does not exist.

- [ ] **Step 3: Implement the API wrapper**

Follow `xiaozhiModels.ts` response validation and redaction conventions. Send:

```typescript
http.get('/volcengine/voices', { params, signal: options?.signal })
```

Reject malformed rows with `ApiProtocolError('火山音色数据格式错误', ...)` and return `{ total, list }`.

- [ ] **Step 4: Run the API tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit the API client**

```bash
git add server/main/companion-console/src/api/volcengineVoices.ts \
  server/main/companion-console/src/api/volcengineVoices.test.ts
git commit -m "feat: add volcengine voice api client"
```

### Task 6: Turn the timbre page into a read-only Volcengine browser

**Files:**
- Modify: `server/main/companion-console/src/pages/voices/TimbreManagementPage.tsx`
- Replace tests in: `server/main/companion-console/src/pages/voices/TimbreManagementPage.test.tsx`

- [ ] **Step 1: Replace the page tests with the new behavior**

Mock `listVolcengineVoices`. Verify the page defaults to 1.0, sends `seed-tts-1.0`, switches to `seed-tts-2.0`, renders normalized metadata, supports name search, plays `trialUrl`, disables unavailable previews, and shows backend failures. Assert that none of the legacy actions exist:

```typescript
expect(screen.queryByRole('button', { name: '新增音色' })).not.toBeInTheDocument()
expect(screen.queryByRole('button', { name: /编辑/ })).not.toBeInTheDocument()
expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument()
```

Version-switch expectation:

```typescript
await user.selectOptions(screen.getByLabelText('语音合成模型'), 'seed-tts-2.0')
await waitFor(() => expect(listVolcengineVoices).toHaveBeenLastCalledWith({
  resourceId: 'seed-tts-2.0', page: 1, limit: 20, name: '',
}, expect.objectContaining({ signal: expect.any(AbortSignal) })))
```

Use Ant Design select interaction helpers if the field renders as a combobox rather than a native select.

- [ ] **Step 2: Run the page test to verify it fails**

```bash
cd server/main/companion-console
npm test -- src/pages/voices/TimbreManagementPage.test.tsx
```

Expected: FAIL because the page still uses `/ttsVoice` and exposes editing actions.

- [ ] **Step 3: Rewrite the page around the catalog API**

Remove imports from `api/timbres` and `api/xiaozhiModels`. Keep the existing reusable audio lifecycle protections. Define fixed version options:

```typescript
const versions = [
  { label: '语音合成 1.0', value: 'seed-tts-1.0' },
  { label: '语音合成 2.0', value: 'seed-tts-2.0' },
] as const
```

Render only the search field, model version selector, query/reset actions, metadata table, pagination, and preview action. Table columns must cover name, voice code, gender, age, languages, tags, description, and preview. Render tags with `Tag`; render absent values as `-`; render unavailable trial URLs as `暂无试听`.

Keep the request abort and stale-response guards already present in the page. Version changes reset pagination to page 1 and immediately reload.

- [ ] **Step 4: Run the page test**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit the read-only page**

```bash
git add server/main/companion-console/src/pages/voices/TimbreManagementPage.tsx \
  server/main/companion-console/src/pages/voices/TimbreManagementPage.test.tsx
git commit -m "feat: make voice catalog volcengine only"
```

### Task 7: Remove the voice-resource tab and legacy entry from the UI

**Files:**
- Modify: `server/main/companion-console/src/pages/voices/VoiceManagementPage.tsx`
- Modify: `server/main/companion-console/src/pages/voices/VoiceManagementPage.test.tsx`
- Modify: `server/main/companion-console/src/app/router.tsx`
- Modify: `server/main/companion-console/src/app/router.test.tsx`

- [ ] **Step 1: Write failing navigation tests**

Update administrator expectations to exactly two tabs, `音色库` and `音色克隆`. Verify `?tab=resources` normalizes to `?tab=timbres`, and `/admin/voice-resources` redirects to `/voices?tab=timbres` while preserving unrelated query parameters.

```typescript
expect(screen.queryByRole('tab', { name: '音色资源' })).not.toBeInTheDocument()
await waitFor(() => expect(location).toHaveTextContent('/voices?tab=timbres'))
```

Keep ordinary users limited to clone access.

- [ ] **Step 2: Run the navigation tests to verify they fail**

```bash
cd server/main/companion-console
npm test -- src/pages/voices/VoiceManagementPage.test.tsx src/app/router.test.tsx
```

Expected: FAIL because the resource tab and redirect still exist.

- [ ] **Step 3: Remove the visible resource route behavior**

Delete the `VoiceResourcePanel` import and resource tab definition from `VoiceManagementPage.tsx`. Normalize unknown or removed administrator tabs to `timbres`.

Change the legacy route to:

```tsx
{ path: 'admin/voice-resources', element: <RequireAdminRedirect to="/voices?tab=timbres" preserveSearch /> },
```

When `preserveSearch` merges a legacy `tab` value, ensure the canonical destination overwrites it with `timbres`.

- [ ] **Step 4: Run the navigation tests**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit the navigation cleanup**

```bash
git add server/main/companion-console/src/pages/voices/VoiceManagementPage.tsx \
  server/main/companion-console/src/pages/voices/VoiceManagementPage.test.tsx \
  server/main/companion-console/src/app/router.tsx \
  server/main/companion-console/src/app/router.test.tsx
git commit -m "feat: remove voice resource management entry"
```

### Task 8: Verify the TTS model editor exposes only the supported catalog

**Files:**
- Modify: `server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx`
- Modify if required: `server/main/companion-console/src/pages/models/ModelFieldEditor.tsx`
- Modify if required: `server/main/companion-console/src/pages/models/modelEditorMetadata.ts`

- [ ] **Step 1: Add model-management regression tests**

Supply API mocks containing unsupported TTS rows to confirm the page only trusts the filtered backend response and does not add local legacy options. Test the unified Volcengine provider fields:

```typescript
const volcengineProvider: ModelProvider = {
  id: 'SYSTEM_TTS_HSDSTTS',
  modelType: 'TTS',
  providerCode: 'huoshan_double_stream',
  name: '火山引擎',
  fields: [
    { key: 'resource_id', label: '模型版本', type: 'string', options: [
      { label: '语音合成 1.0', value: 'seed-tts-1.0' },
      { label: '语音合成 2.0', value: 'seed-tts-2.0' },
    ] },
    { key: 'appid', label: '应用ID', type: 'string' },
    { key: 'access_token', label: '访问令牌', type: 'password' },
    { key: 'access_key_id', label: 'Access Key ID', type: 'string' },
    { key: 'secret_access_key', label: 'Secret Access Key', type: 'password' },
  ],
  sort: 2, updater: null, updateDate: null, creator: null, createDate: null,
}
```

Verify the drawer shows both version labels and all four credential fields. Verify saved masked values are not resubmitted as changed secrets.

- [ ] **Step 2: Run the model page test**

```bash
cd server/main/companion-console
npm test -- src/pages/models/ModelManagementPage.test.tsx src/pages/models/ModelFieldEditor.test.tsx
```

Expected: PASS if the existing options editor handles object options; otherwise the new test exposes the exact compatibility gap.

- [ ] **Step 3: Make the smallest editor compatibility change if needed**

Normalize options in `ModelFieldEditor.tsx` so both primitive options and `{ label, value }` options work:

```typescript
const options = field.options?.map((option) => {
  if (typeof option === 'string' || typeof option === 'number') {
    return { label: String(option), value: option }
  }
  if (option && typeof option === 'object' && 'label' in option && 'value' in option) {
    return option as { label: string; value: unknown }
  }
  return null
}).filter((option): option is { label: string; value: unknown } => Boolean(option))
```

Do not introduce a Volcengine-specific editor component.

- [ ] **Step 4: Run the model tests again**

Run the command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit model editor coverage**

```bash
git add server/main/companion-console/src/pages/models/ModelManagementPage.test.tsx \
  server/main/companion-console/src/pages/models/ModelFieldEditor.tsx \
  server/main/companion-console/src/pages/models/ModelFieldEditor.test.tsx \
  server/main/companion-console/src/pages/models/modelEditorMetadata.ts
git commit -m "test: cover volcengine tts model versions"
```

Only stage files that actually changed.

### Task 9: Lock down Volcengine 1.0 and 2.0 synthesis headers

**Files:**
- Create: `server/main/xiaozhi-server/tests/test_huoshan_double_stream.py`
- Modify only if the test exposes a defect: `server/main/xiaozhi-server/core/providers/tts/huoshan_double_stream.py`

- [ ] **Step 1: Write the provider tests**

Patch `websockets.connect`, instantiate the provider twice, and assert both versions use the same URL with different resource headers:

```python
class HuoshanDoubleStreamVersionTest(unittest.IsolatedAsyncioTestCase):
    async def test_uses_shared_endpoint_for_tts_1_and_2(self):
        for resource_id in ("seed-tts-1.0", "seed-tts-2.0"):
            provider = TTSProvider({
                "appid": "app-id",
                "access_token": "access-token",
                "resource_id": resource_id,
                "ws_url": "wss://openspeech.bytedance.com/api/v3/tts/bidirection",
                "speaker": "voice-code",
            }, True)

            with patch("core.providers.tts.huoshan_double_stream.websockets.connect", new_callable=AsyncMock) as connect:
                connect.return_value = AsyncMock()
                await provider._ensure_connection()

            _, kwargs = connect.call_args
            self.assertEqual("wss://openspeech.bytedance.com/api/v3/tts/bidirection", connect.call_args.args[0])
            self.assertEqual(resource_id, kwargs["additional_headers"]["X-Api-Resource-Id"])
```

Add assertions that `report_on_last` is false for 1.0 and true for 2.0.

- [ ] **Step 2: Run the test**

```bash
cd server/main/xiaozhi-server
python -m unittest tests.test_huoshan_double_stream
```

Expected: PASS with the existing provider. If it fails, make only the minimal version comparison or header fix and rerun.

- [ ] **Step 3: Commit the synthesis regression test**

```bash
git add server/main/xiaozhi-server/tests/test_huoshan_double_stream.py \
  server/main/xiaozhi-server/core/providers/tts/huoshan_double_stream.py
git commit -m "test: cover volcengine tts resource versions"
```

Only stage the provider when its implementation changed.

### Task 10: Run full verification and review the diff

**Files:**
- Verify all files changed in Tasks 1 through 9

- [ ] **Step 1: Run the manager API test suite**

```bash
cd server/main/manager-api
mvn -DskipTests=false test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run the console test suite**

```bash
cd server/main/companion-console
npm test
```

Expected: all Vitest suites pass.

- [ ] **Step 3: Build and lint the console**

```bash
cd server/main/companion-console
npm run build
npm run lint
```

Expected: both commands exit 0.

- [ ] **Step 4: Run the Python regression suite**

```bash
cd server/main/xiaozhi-server
python -m unittest discover -s tests
```

Expected: all unit tests pass.

- [ ] **Step 5: Review repository state and scope**

```bash
git status --short
git diff --check
git diff --stat HEAD~9..HEAD
```

Expected: no whitespace errors; `mqtt-gateway/start-local.sh` and `mqtt-gateway/tests/` remain untouched and uncommitted.

- [ ] **Step 6: Perform a final requirement audit**

Confirm the console has no visible voice-resource tab, no timbre CRUD actions, and no `/ttsVoice` dependency in `TimbreManagementPage.tsx`. Confirm backend TTS management returns only the three allowed model IDs and providers. Confirm both Volcengine versions use the shared WebSocket endpoint and the catalog reads only `ListSpeakers`.

- [ ] **Step 7: Commit any final test-only corrections**

```bash
git add <only-the-files-corrected-during-verification>
git commit -m "test: verify volcengine voice management"
```

Skip this commit when verification required no changes.
