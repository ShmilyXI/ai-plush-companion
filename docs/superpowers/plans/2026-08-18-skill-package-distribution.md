# Skill Package Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace database-form Skill definitions with installable, exportable `.skill.zip` packages while preserving existing Plugin, MCP, device binding, and runtime behavior.

**Architecture:** `manager-api` owns package parsing, validation, deterministic building, local storage, immutable publication, and migration. The database keeps package metadata plus a normalized runtime projection; `xiaozhi-server` consumes only that projection. `companion-console` supports package upload and an online package editor that both produce the same archive.

**Tech Stack:** Java 21, Spring Boot 3.4, MyBatis Plus, SnakeYAML, MySQL, React 19, TypeScript, Ant Design, Vitest, Python 3, pytest.

---

## File Map

New package-domain files live under `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/`. Parsing, validation, building, and storage remain separate classes so archive security is not mixed into capability CRUD.

The existing `CapabilityServiceImpl` remains the transaction boundary for online Skill CRUD and publication. Package operations are exposed through a new `SkillPackageService` and controller methods on `AdminCapabilityController`.

The frontend keeps `CapabilityManagementPage` as the page owner. `SkillPackageImportModal` handles upload and compatibility completion. `SkillEditorModal` becomes the online package editor and edits manifest fields plus `SKILL.md`.

### Task 1: Add Package Persistence

**Files:**

- Create: `server/main/manager-api/src/main/resources/db/changelog/202608181500.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608181500-rollback.sql`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/SkillPackageEntity.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/SkillPackageDao.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageVO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityVO.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageSchemaContractTest.java`

- [ ] **Step 1: Write the failing schema contract test**

```java
@Test
void migrationCreatesImmutableSkillPackageMetadata() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608181500.sql"));
    assertTrue(sql.contains("CREATE TABLE `ai_skill_package`"));
    assertTrue(sql.contains("UNIQUE KEY `uk_skill_package_version` (`capability_id`,`version_no`)"));
    assertTrue(sql.contains("`package_sha256` char(64) NOT NULL"));
    assertTrue(sql.contains("`storage_key` varchar(500) NOT NULL"));
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageSchemaContractTest test`

Expected: FAIL because the migration does not exist.

- [ ] **Step 3: Add the table and Java mapping**

```sql
CREATE TABLE `ai_skill_package` (
  `id` varchar(32) NOT NULL,
  `capability_id` varchar(32) NOT NULL,
  `version_no` int NOT NULL,
  `package_sha256` char(64) NOT NULL,
  `package_size` bigint NOT NULL,
  `storage_key` varchar(500) NOT NULL,
  `manifest_json` longtext NOT NULL,
  `skill_markdown` longtext NOT NULL,
  `source_type` varchar(24) NOT NULL,
  `validation_status` varchar(24) NOT NULL,
  `validation_report_json` longtext NOT NULL,
  `published` tinyint NOT NULL DEFAULT 0,
  `creator` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_package_version` (`capability_id`,`version_no`),
  CONSTRAINT `fk_skill_package_capability` FOREIGN KEY (`capability_id`)
    REFERENCES `ai_capability` (`id`) ON DELETE CASCADE
);
```

Expose package version, SHA-256, source, validation status, and validation issues on `CapabilityVO`. Do not expose `storageKey`.

- [ ] **Step 4: Run the contract test**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageSchemaContractTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/resources/db/changelog/202608181500* \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/entity/SkillPackageEntity.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/SkillPackageDao.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageVO.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityVO.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageSchemaContractTest.java
git commit -m "feat: add skill package persistence"
```

### Task 2: Build Secure Package Parsing and Storage

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageLimits.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageDocument.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageParser.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageBuilder.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageStore.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/LocalSkillPackageStore.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageParserTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageBuilderTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/LocalSkillPackageStoreTest.java`

- [ ] **Step 1: Write failing archive security tests**

```java
@ParameterizedTest
@ValueSource(strings = {"../secret.txt", "/tmp/secret.txt", "scripts/run.py", "bin/tool"})
void rejectsUnsafeEntries(String name) {
    byte[] archive = zip(Map.of(name, "payload"));
    assertThrows(RenException.class, () -> parser.parse(archive));
}

@Test
void rejectsArchivePastExpandedLimit() {
    byte[] archive = zip(Map.of("SKILL.md", "x".repeat(31 * 1024 * 1024)));
    assertThrows(RenException.class, () -> parser.parse(archive));
}
```

- [ ] **Step 2: Run parser tests and verify they fail**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageParserTest test`

Expected: FAIL because package classes do not exist.

- [ ] **Step 3: Implement bounded parsing**

```java
public record SkillPackageDocument(
        Map<String, Object> manifest,
        String markdown,
        Map<String, byte[]> assets,
        String sha256,
        long archiveSize) {}

public SkillPackageDocument parse(byte[] archive) {
    requireArchiveSize(archive.length, 10L * 1024 * 1024);
    // Iterate ZipInputStream once, normalize every path, reject traversal,
    // duplicates, links, nested archives, forbidden extensions, >200 files,
    // >5 MiB per file, and >30 MiB total expanded bytes.
    // Require UTF-8 skill.yaml and SKILL.md at the archive root.
}
```

Use SnakeYAML `SafeConstructor` with duplicate keys disabled. Reject unknown top-level manifest fields. Do not extract untrusted entries to disk.

- [ ] **Step 4: Implement deterministic building**

```java
public byte[] build(Map<String, Object> manifest, String markdown,
        Map<String, byte[]> assets) {
    // Sort paths, normalize LF, emit UTF-8, set every ZipEntry timestamp to 0,
    // then return one canonical archive for stable SHA-256 values.
}
```

Add a test that builds the same input twice and asserts identical bytes and SHA-256.

- [ ] **Step 5: Implement managed local storage**

```java
public interface SkillPackageStore {
    String put(String skillId, int version, String sha256, byte[] bytes);
    byte[] get(String storageKey);
    void delete(String storageKey);
}
```

Store under `data/skill-packages/<skillId>/<version>/<sha256>.skill.zip`. Resolve and normalize every path under the configured root, write through a temporary sibling, then atomically move. Never return an absolute path through the API.

- [ ] **Step 6: Run package unit tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageParserTest,SkillPackageBuilderTest,LocalSkillPackageStoreTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackage*Test.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/LocalSkillPackageStoreTest.java
git commit -m "feat: parse and store skill packages safely"
```

### Task 3: Validate Manifests Against Existing Capabilities

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageValidator.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageValidationVO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/DeviceToolSnapshotDao.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageValidatorTest.java`

- [ ] **Step 1: Write failing validation tests**

```java
@Test
void rejectsUnknownPluginToolAndInlineSecret() {
    SkillPackageDocument document = packageWithTool("PLUGIN", "missing-plugin", "get_weather");
    document.manifest().put("apiKey", "secret-value");
    SkillPackageValidationVO report = validator.validate(document, null);
    assertEquals("INVALID", report.getStatus());
    assertEquals(List.of("UNKNOWN_TOOL", "INLINE_SECRET"), report.errorCodes());
}

@Test
void acceptsApprovedPluginMcpAndDeviceToolReferences() {
    assertEquals("VALID", validator.validate(validDocument(), "skill-weather").getStatus());
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageValidatorTest test`

Expected: FAIL because the validator does not exist.

- [ ] **Step 3: Implement strict validation**

```java
public SkillPackageValidationVO validate(SkillPackageDocument document, String existingSkillId) {
    // Validate schemaVersion, immutable id, positive version, runtime fields,
    // triggers, tool references, defaults, overridable fields, device requirements,
    // declared secrets, and recursively detect inline secret-like values.
}
```

Resolve Plugin tools through `ai_plugin_definition`, external MCP tools through approved `ai_mcp_tool_snapshot` rows, role MCP tools through the agent MCP registry, and device tools through reported names. `required=true` makes an unresolved tool an error. Optional device and role MCP tools remain valid but produce a warning when their current runtime is unavailable.

- [ ] **Step 4: Run validator tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageValidatorTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/packagefile/SkillPackageValidator.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageValidationVO.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dao/DeviceToolSnapshotDao.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageValidatorTest.java
git commit -m "feat: validate skill package dependencies"
```

### Task 4: Make Packages the Skill CRUD and Publication Source

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/SkillPackageDraftDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/SkillPackageService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/SkillPackageServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityVO.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageServiceImplTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityServiceImplTest.java`

- [ ] **Step 1: Write failing package-first CRUD tests**

```java
@Test
void onlineSkillSaveBuildsCanonicalDraftPackage() {
    CapabilityVO created = service.create(7L, skillRequest("天气", "查天气"));
    SkillPackageEntity row = packages.selectDraft(created.getId());
    assertEquals(1, row.getVersionNo());
    assertEquals("VALID", row.getValidationStatus());
    assertTrue(row.getSkillMarkdown().contains("查天气"));
}

@Test
void publishUsesDraftPackageProjectionAndMakesVersionImmutable() {
    CapabilityVO published = service.publish(7L, "skill-weather");
    assertEquals(packages.selectByVersion("skill-weather", 1).getPackageSha256(),
            versions.selectVersion("skill-weather", 1).getContentSha256());
    assertThrows(RenException.class, () -> packages.replacePublished("skill-weather", 1));
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageServiceImplTest,CapabilityServiceImplTest test`

Expected: FAIL because Skill saves still write independent tables.

- [ ] **Step 3: Implement package-first draft saves**

```java
public SkillPackageVO saveOnlineDraft(Long operatorId, String capabilityId,
        int targetVersion, SkillPackageDraftDTO draft) {
    Map<String, Object> manifest = manifestFrom(draft, capabilityId, targetVersion);
    byte[] archive = builder.build(manifest, draft.getSkillMarkdown(), Map.of());
    SkillPackageDocument parsed = parser.parse(archive);
    SkillPackageValidationVO report = validator.validate(parsed, capabilityId);
    requireValid(report);
    return persistDraft(operatorId, capabilityId, targetVersion, parsed, archive, "ONLINE");
}
```

`CapabilityServiceImpl.persistSkill` must delegate to this method, then refresh `ai_skill_definition`, trigger, and tool rows from the parsed projection in the same transaction. No controller may write these projection tables independently.

- [ ] **Step 4: Publish the exact draft package**

On publish, lock the draft row, verify its stored bytes and SHA-256, insert the normalized projection into `ai_capability_version`, mark the package published, and bump `LATEST` device bindings. Reject invalid or missing packages.

- [ ] **Step 5: Run service tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageServiceImplTest,CapabilityServiceImplTest,DeviceCapabilityServiceImplTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/dto/SkillPackageDraftDTO.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/SkillPackageService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/SkillPackageServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/CapabilityVO.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageServiceImplTest.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityServiceImplTest.java
git commit -m "feat: make skill packages the publication source"
```

### Task 5: Add Upload, Compatibility Import, Download, and Validation APIs

**Files:**

- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/controller/AdminCapabilityController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/SkillPackageService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/SkillPackageServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageImportVO.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageControllerTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/AdminCapabilityControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

```java
mvc.perform(multipart("/admin/companion/capabilities/skill-packages/import")
        .file(new MockMultipartFile("file", "weather.skill.zip", "application/zip", archive)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.validation.status").value("VALID"));

mvc.perform(get("/admin/companion/capabilities/skill-weather/packages/1/download"))
    .andExpect(status().isOk())
    .andExpect(header().string("Content-Disposition", containsString("weather.skill.zip")));
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageControllerTest,AdminCapabilityControllerTest test`

Expected: FAIL with missing routes.

- [ ] **Step 3: Add the endpoints**

```java
@PostMapping(path = "/skill-packages/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Result<SkillPackageImportVO> importPackage(@RequestParam("file") MultipartFile file) {
    return new Result<SkillPackageImportVO>().ok(skillPackages.importArchive(SecurityUser.getUserId(), file));
}

@PostMapping(path = "/{id}/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Result<CapabilityVO> savePackage(@PathVariable String id,
        @RequestParam("file") MultipartFile file) {
    return new Result<CapabilityVO>().ok(skillPackages.saveDraft(SecurityUser.getUserId(), id, file));
}

@GetMapping("/{id}/packages/{version}/download")
public ResponseEntity<Resource> downloadPackage(@PathVariable String id,
        @PathVariable int version) {
    return skillPackages.download(id, version);
}

@GetMapping("/{id}/packages/draft/validation")
public Result<SkillPackageValidationVO> validation(@PathVariable String id) {
    return new Result<SkillPackageValidationVO>().ok(skillPackages.draftValidation(id));
}
```

`importPackage` returns a completed draft for formal packages and an `INCOMPLETE` draft projection for packages containing only `SKILL.md`. Every endpoint requires `sys:role:superAdmin`.

- [ ] **Step 4: Run controller tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=SkillPackageControllerTest,AdminCapabilityControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/controller/AdminCapabilityController.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/SkillPackageService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/SkillPackageServiceImpl.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/SkillPackageImportVO.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageControllerTest.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/AdminCapabilityControllerTest.java
git commit -m "feat: expose skill package management APIs"
```

### Task 6: Migrate Existing Skills Into Packages

**Files:**

- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/LegacySkillPackageMigrationService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityMigrationAuditService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityMigrationAuditServiceImpl.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/LegacySkillPackageMigrationServiceTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityBootstrapServiceTest.java`

- [ ] **Step 1: Write failing idempotent migration tests**

```java
@Test
void migratesEveryHistoricalVersionWithoutChangingBindings() {
    migration.migrate();
    assertNotNull(packages.selectByVersion("skill-weather", 1));
    assertNotNull(packages.selectByVersion("skill-weather", 2));
    verify(bindings, never()).deleteBySkillId(any());
}

@Test
void rerunDoesNotDuplicatePackages() {
    migration.migrate();
    migration.migrate();
    assertEquals(2, packages.selectByCapability("skill-weather").size());
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=LegacySkillPackageMigrationServiceTest,CapabilityBootstrapServiceTest test`

Expected: FAIL because package migration is absent.

- [ ] **Step 3: Implement historical conversion**

For each Skill version, parse `CapabilityVersionEntity.contentJson`, build `skill.yaml` and `SKILL.md`, store the canonical archive, and insert a published `ai_skill_package` row with the original version. Build the current unpublished draft from projection tables only after all historical versions succeed.

```java
if (packages.selectByVersion(skillId, versionNo) == null) {
    packageService.importLegacyPublished(operatorId, skillId, versionNo, contentJson);
}
```

Do not modify `ai_device_skill_mapping`. Add migration audit counts for migrated, invalid, and missing packages.

- [ ] **Step 4: Run migration tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=LegacySkillPackageMigrationServiceTest,CapabilityBootstrapServiceTest,CapabilityMigrationAuditServiceImplTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/LegacySkillPackageMigrationService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/init/CapabilityBootstrapService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/CapabilityMigrationAuditService.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/CapabilityMigrationAuditServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/LegacySkillPackageMigrationServiceTest.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/CapabilityBootstrapServiceTest.java
git commit -m "feat: migrate legacy skills into packages"
```

### Task 7: Carry Package Identity Through the Runtime Projection

**Files:**

- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/EffectiveCapabilityBundleVO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/DeviceCapabilityServiceImpl.java`
- Modify: `server/main/xiaozhi-server/core/capabilities/models.py`
- Modify: `server/main/xiaozhi-server/tests/test_skill_tool_isolation.py`
- Modify: `server/main/xiaozhi-server/tests/test_connection_tool_routing.py`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/DeviceCapabilityServiceImplTest.java`

- [ ] **Step 1: Write failing projection tests**

```java
assertEquals("abc123", bundle.getSkills().get(0).getPackageSha256());
assertEquals(3, bundle.getSkills().get(0).getPackageVersion());
assertEquals(markdown, bundle.getSkills().get(0).getExecutionPrompt());
```

```python
skill = CapabilityBundle.parse(payload).skills[0]
assert skill.package_sha256 == "abc123"
assert skill.package_version == 3
assert skill.execution_prompt == "# Weather\nUse get_weather."
```

- [ ] **Step 2: Run Java and Python tests and verify they fail**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=DeviceCapabilityServiceImplTest test`

Run: `cd server/main/xiaozhi-server && python -m pytest tests/test_skill_tool_isolation.py tests/test_connection_tool_routing.py -q`

Expected: FAIL because package fields are absent.

- [ ] **Step 3: Extend the projection and strict parser**

```python
@dataclass(frozen=True)
class Skill:
    id: str
    version: int
    package_version: int
    package_sha256: str
    # existing fields remain
```

Build `executionPrompt`, triggers, tool names, defaults, package version, and digest from the selected published package projection. Do not read current draft tables for device bundles.

- [ ] **Step 4: Run runtime tests**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest=DeviceCapabilityServiceImplTest,InternalCapabilityControllerTest test`

Run: `cd server/main/xiaozhi-server && python -m pytest tests/test_skill_tool_isolation.py tests/test_connection_tool_routing.py -q`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/vo/EffectiveCapabilityBundleVO.java \
  server/main/manager-api/src/main/java/xiaozhi/modules/companion/capability/service/impl/DeviceCapabilityServiceImpl.java \
  server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/DeviceCapabilityServiceImplTest.java \
  server/main/xiaozhi-server/core/capabilities/models.py \
  server/main/xiaozhi-server/tests/test_skill_tool_isolation.py \
  server/main/xiaozhi-server/tests/test_connection_tool_routing.py
git commit -m "feat: project skill package identity to runtime"
```

### Task 8: Add Frontend Package APIs and Import Flow

**Files:**

- Modify: `server/main/companion-console/src/api/capabilities.ts`
- Modify: `server/main/companion-console/src/api/capabilities.test.ts`
- Create: `server/main/companion-console/src/pages/admin/SkillPackageImportModal.tsx`
- Create: `server/main/companion-console/src/pages/admin/SkillPackageImportModal.test.tsx`

- [ ] **Step 1: Write failing API and modal tests**

```ts
await importSkillPackage(new File([archive], 'weather.skill.zip'))
expect(http.post).toHaveBeenCalledWith(
  '/admin/companion/capabilities/skill-packages/import',
  expect.any(FormData),
  expect.objectContaining({ headers: { 'Content-Type': 'multipart/form-data' } }),
)
```

```tsx
render(<SkillPackageImportModal open onCancel={vi.fn()} onImported={onImported} />)
await user.upload(screen.getByLabelText('Skill 包'), file)
expect(await screen.findByText('校验通过')).toBeInTheDocument()
expect(screen.getByText('get_weather')).toBeInTheDocument()
```

- [ ] **Step 2: Run frontend tests and verify they fail**

Run: `cd server/main/companion-console && npm test -- src/api/capabilities.test.ts src/pages/admin/SkillPackageImportModal.test.tsx`

Expected: FAIL with missing API and component.

- [ ] **Step 3: Add package types and HTTP methods**

```ts
export interface SkillPackageValidation {
  status: 'VALID' | 'INVALID' | 'INCOMPLETE'
  issues: { level: 'ERROR' | 'WARNING'; code: string; message: string }[]
}

export async function importSkillPackage(file: File, options?: RequestOptions) {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResult<unknown>>(`${base}/skill-packages/import`, form, requestConfig(options))
  return parseSkillPackageImport(unwrap(response), response)
}

export async function uploadSkillPackage(id: string, file: File, options?: RequestOptions) {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResult<unknown>>(`${base}/${encoded(id)}/packages`, form, requestConfig(options))
  return parseCapability(unwrap(response), response)
}

export async function downloadSkillPackage(id: string, version: number, options?: RequestOptions) {
  return http.get<Blob>(`${base}/${encoded(id)}/packages/${version}/download`, {
    responseType: 'blob', ...requestConfig(options),
  })
}
```

- [ ] **Step 4: Build the import modal**

Use Ant Design `Upload.Dragger`, display parsed metadata, dependency mappings, and validation issues. For `INCOMPLETE`, show the same tool picker used by the online editor before saving the formal draft. Never display secret values.

- [ ] **Step 5: Run API and modal tests**

Run: `cd server/main/companion-console && npm test -- src/api/capabilities.test.ts src/pages/admin/SkillPackageImportModal.test.tsx`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/main/companion-console/src/api/capabilities.ts \
  server/main/companion-console/src/api/capabilities.test.ts \
  server/main/companion-console/src/pages/admin/SkillPackageImportModal.tsx \
  server/main/companion-console/src/pages/admin/SkillPackageImportModal.test.tsx
git commit -m "feat: add skill package import flow"
```

### Task 9: Turn the Skill Editor Into a Package Editor

**Files:**

- Modify: `server/main/companion-console/src/pages/admin/SkillEditorModal.tsx`
- Modify: `server/main/companion-console/src/pages/admin/SkillEditorModal.test.tsx`
- Modify: `server/main/companion-console/src/pages/admin/CapabilityManagementPage.tsx`
- Modify: `server/main/companion-console/src/pages/admin/CapabilityManagementPage.test.tsx`
- Modify: `server/main/companion-console/src/styles.css`

- [ ] **Step 1: Write failing package editor tests**

```tsx
expect(screen.getByRole('tab', { name: '执行说明' })).toBeInTheDocument()
expect(screen.getByRole('tab', { name: '清单预览' })).toBeInTheDocument()
await user.click(screen.getByRole('button', { name: '上传 Skill 包' }))
expect(screen.getByRole('dialog', { name: '导入 Skill 包' })).toBeInTheDocument()
```

Assert that saving online sends `skillMarkdown`, manifest fields, and selected tools, and that published rows expose a download action with their exact version.

- [ ] **Step 2: Run UI tests and verify they fail**

Run: `cd server/main/companion-console && npm test -- src/pages/admin/SkillEditorModal.test.tsx src/pages/admin/CapabilityManagementPage.test.tsx`

Expected: FAIL because package controls are absent.

- [ ] **Step 3: Refine the editor**

Replace the single execution prompt textarea with tabs for package settings, `SKILL.md`, and read-only `skill.yaml`. Keep triggers and tools as structured controls. Use `FileUp`, `Download`, and `Package` icons from the existing Ant Design icon library.

```tsx
<Tabs items={[
  { key: 'manifest', label: '包设置', children: <ManifestFields /> },
  { key: 'markdown', label: '执行说明', children: <Input.TextArea aria-label="SKILL.md" /> },
  { key: 'preview', label: '清单预览', children: <pre>{manifestYaml}</pre> },
]} />
```

- [ ] **Step 4: Integrate import and download**

Add `上传 Skill 包` beside `在线创建 Skill`. Add package SHA, source, validation status, and version download to the Skill table without turning the page into nested cards.

- [ ] **Step 5: Run focused UI tests and build**

Run: `cd server/main/companion-console && npm test -- src/pages/admin/SkillEditorModal.test.tsx src/pages/admin/SkillPackageImportModal.test.tsx src/pages/admin/CapabilityManagementPage.test.tsx`

Run: `cd server/main/companion-console && npm run build`

Expected: tests and build PASS.

- [ ] **Step 6: Commit**

```bash
git add server/main/companion-console/src/pages/admin/SkillEditorModal.tsx \
  server/main/companion-console/src/pages/admin/SkillEditorModal.test.tsx \
  server/main/companion-console/src/pages/admin/CapabilityManagementPage.tsx \
  server/main/companion-console/src/pages/admin/CapabilityManagementPage.test.tsx \
  server/main/companion-console/src/styles.css
git commit -m "feat: manage skill packages in capability center"
```

### Task 10: Verify Migration, Isolation, and Round Trip

**Files:**

- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageRoundTripIntegrationTest.java`
- Create: `server/main/xiaozhi-server/tests/test_skill_package_projection.py`
- Modify: `docs/superpowers/specs/2026-08-18-skill-package-distribution-design.md`

- [ ] **Step 1: Add the round-trip integration test**

```java
@Test
void uploadPublishDownloadReimportPreservesCanonicalPackage() {
    SkillPackageImportVO imported = packages.importArchive(7L, weatherArchive());
    capabilities.publish(7L, imported.getCapabilityId());
    byte[] exported = packages.download(imported.getCapabilityId(), 1).bytes();
    assertArrayEquals(weatherArchive(), exported);
}
```

The test must also bind the Skill to one device, verify another device lacks it, publish version two, and assert `LATEST` moves while `FIXED` remains on version one.

- [ ] **Step 2: Add Python projection isolation coverage**

```python
def test_package_projection_only_exposes_declared_tools():
    bundle = CapabilityBundle.parse(package_payload())
    turn = asyncio.run(SkillTurnRuntime().select(bundle, "深圳天气", RuleOnlyClassifier()))
    assert turn.allowed_tool_names == frozenset({"handle_exit_intent", "get_weather"})
    assert "web_search" not in turn.allowed_tool_names
```

- [ ] **Step 3: Run the full relevant suites**

Run: `cd server/main/manager-api && mvn -DskipTests=false -Dtest='*Capability*,*SkillPackage*' test`

Run: `cd server/main/xiaozhi-server && python -m pytest tests/test_skill_package_projection.py tests/test_skill_tool_isolation.py tests/test_connection_tool_routing.py -q`

Run: `cd server/main/companion-console && npm test -- src/api/capabilities.test.ts src/pages/admin/SkillEditorModal.test.tsx src/pages/admin/SkillPackageImportModal.test.tsx src/pages/admin/CapabilityManagementPage.test.tsx src/pages/devices/DeviceSkillCard.test.tsx`

Run: `cd server/main/companion-console && npm run build`

Expected: all commands PASS with no skipped package-security or device-isolation tests.

- [ ] **Step 4: Run a local end-to-end verification**

Start the existing manager API, Python server, and console. Upload a weather package, publish it, bind it only to device `7c:0c:5f:40:49:54`, then call route preview with `深圳天气怎么样`. Verify the selected Skill and allowed tool are `skill-weather` and `get_weather`. Download the package and compare its SHA-256 with the API metadata.

Use the existing device command and logs to confirm volume, brightness, and camera tools still work. Do not infer device success from HTTP status alone; require the MCP result payload.

- [ ] **Step 5: Record exact verification evidence in the design document**

Append the tested package SHA, version, device binding mode, route preview result, test command counts, and any residual environment limitations. Do not include API keys, tokens, or full private prompts.

- [ ] **Step 6: Commit**

```bash
git add server/main/manager-api/src/test/java/xiaozhi/modules/companion/capability/SkillPackageRoundTripIntegrationTest.java \
  server/main/xiaozhi-server/tests/test_skill_package_projection.py \
  docs/superpowers/specs/2026-08-18-skill-package-distribution-design.md
git commit -m "test: verify skill package distribution end to end"
```
