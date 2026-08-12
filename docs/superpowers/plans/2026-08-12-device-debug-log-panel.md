# Device Debug Log Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in device debug log panel that loads the last 24 hours of retained events, follows new events in real time, separates them into useful tabs, and never exposes unavailable model thoughts.

**Architecture:** `manager-api` owns the device switch, authorization, sanitization, Redis Stream retention, history reads, and SSE continuation. `xiaozhi-server` emits bounded structured events through a non-blocking per-connection reporter, while `companion-console` merges history with an authenticated fetch-based SSE stream. Events are stored per device for 24 hours and trimmed to 1000 records; logging failures never affect conversation or device-control paths.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data Redis Streams, Shiro, MyBatis Plus, Liquibase, Python 3, httpx, React 19, TypeScript 5.9, Ant Design 5, Vitest, Testing Library, JUnit 5, Mockito, pytest.

---

## File Structure

`server/main/manager-api/src/main/resources/db/changelog/202608121900.sql` and its rollback add the device-level opt-in flag.

`server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/model/DeviceDebugLogEvent.java` defines the stable event returned to the console. `DeviceDebugLogDraft.java` is the pre-storage event shape used by internal callers.

`server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/dto/DeviceDebugLogIngestDTO.java` and `DeviceDebugLogSettingDTO.java` validate internal ingestion and user switch updates.

`server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/vo/DeviceDebugLogHistoryVO.java` returns retained events and the continuation cursor.

`server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/DeviceDebugLogSanitizer.java` owns redaction and length limits. `DeviceDebugLogStore.java` abstracts persistence. `RedisDeviceDebugLogStore.java` owns Redis Stream append, time trimming, max-length trimming, history reads, and blocking continuation reads. `DeviceDebugLogService.java` owns device resolution, switch checks, ownership, and failure isolation.

`server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/controller/CompanionDeviceDebugLogController.java` exposes owner-only settings, history, and SSE. `InternalDeviceDebugLogController.java` receives service-secret-authenticated events from `xiaozhi-server`.

`server/main/xiaozhi-server/core/debug_events.py` owns the bounded non-blocking reporter. Existing ASR, VAD, LLM, tool, TTS, and connection files only create structured event drafts at stable lifecycle points.

`server/main/companion-console/src/api/deviceDebugLogs.ts` validates history responses and parses authenticated SSE. `DeviceDebugLogPanel.tsx` owns display state, tabs, pause-scroll behavior, and the local clear action. `DeviceDetailPage.tsx` only passes device identity and switch state into the panel.

### Task 1: Persist and expose the device-level logging switch

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608121900.sql`
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608121900-rollback.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/entity/DeviceEntity.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/vo/CompanionDeviceVO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/CompanionDeviceService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionDeviceServiceImpl.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/dto/DeviceDebugLogSettingDTO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionDeviceController.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/entity/CompanionSchemaContractTest.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceServiceImplTest.java`

- [ ] **Step 1: Write failing schema and service tests**

Add a schema assertion that the forward migration contains `debug_log_enabled`, the rollback removes it, and the master changelog includes both files. Add these service assertions:

```java
@Test
void deviceDetailReturnsDebugLoggingDisabledByDefault() {
    DeviceService deviceService = mock(DeviceService.class);
    DeviceEntity device = ownedDevice();
    device.setDebugLogEnabled(null);
    when(deviceService.selectById("device-a")).thenReturn(device);
    CompanionDeviceService service = new CompanionDeviceServiceImpl(
            deviceService, mock(CompanionProfileService.class));

    assertEquals(false, service.get(7L, "device-a").getDebugLogEnabled());
}

@Test
void ownerCanEnableDebugLoggingWithoutChangingOtherDeviceFields() {
    DeviceService deviceService = mock(DeviceService.class);
    when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
    when(deviceService.update(any(DeviceEntity.class), any())).thenReturn(true);
    CompanionDeviceService service = new CompanionDeviceServiceImpl(
            deviceService, mock(CompanionProfileService.class));

    service.setDebugLogEnabled(7L, "device-a", true);

    ArgumentCaptor<DeviceEntity> changed = ArgumentCaptor.forClass(DeviceEntity.class);
    verify(deviceService).update(changed.capture(), any());
    assertEquals(1, changed.getValue().getDebugLogEnabled());
    assertNull(changed.getValue().getAlias());
    assertNull(changed.getValue().getAgentId());
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=CompanionSchemaContractTest,CompanionDeviceServiceImplTest test
```

Expected: FAIL because the migration, entity field, VO property, and service method do not exist.

- [ ] **Step 3: Add the migration and data properties**

Use this forward migration:

```sql
-- liquibase formatted sql

-- changeset Codex:202608121900
ALTER TABLE `ai_device`
    ADD COLUMN `debug_log_enabled` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否记录设备调试日志(0关闭/1开启)' AFTER `has_camera`;
```

Use this rollback:

```sql
ALTER TABLE `ai_device`
    DROP COLUMN `debug_log_enabled`;
```

Append a master changelog entry with rollback, then add this property to both `DeviceEntity` and `CompanionDeviceVO`:

```java
@Schema(description = "是否记录设备调试日志(0关闭/1开启)")
private Integer debugLogEnabled;
```

Use `Boolean` in `CompanionDeviceVO`.

- [ ] **Step 4: Add the owner-only switch method and endpoint**

Create the validated request:

```java
package xiaozhi.modules.companion.debug.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceDebugLogSettingDTO {
    @NotNull
    private Boolean enabled;
}
```

Add to `CompanionDeviceService`:

```java
void setDebugLogEnabled(Long userId, String deviceId, boolean enabled);
```

Implement with the same owned update predicate used by rename:

```java
@Override
public void setDebugLogEnabled(Long userId, String deviceId, boolean enabled) {
    requireOwned(userId, deviceId);
    DeviceEntity changed = new DeviceEntity();
    changed.setId(deviceId);
    changed.setDebugLogEnabled(enabled ? 1 : 0);
    UpdateWrapper<DeviceEntity> owned = new UpdateWrapper<DeviceEntity>()
            .eq("id", deviceId)
            .eq("user_id", userId);
    if (!deviceService.update(changed, owned)) {
        throw new RenException(ErrorCode.UPDATE_DATA_FAILED);
    }
}
```

Map the VO with `Integer.valueOf(1).equals(device.getDebugLogEnabled())`, then add:

```java
@PutMapping("/{id}/debug-logs/settings")
@RequiresPermissions("sys:role:normal")
public Result<Void> updateDebugLogSetting(
        @PathVariable String id,
        @RequestBody @Valid DeviceDebugLogSettingDTO dto) {
    deviceService.setDebugLogEnabled(SecurityUser.getUserId(), id, dto.getEnabled());
    return new Result<Void>().ok(null);
}
```

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the command from Step 2.

Expected: both test classes pass.

- [ ] **Step 6: Commit the switch contract**

```bash
git add server/main/manager-api/src/main/resources/db/changelog/202608121900.sql server/main/manager-api/src/main/resources/db/changelog/202608121900-rollback.sql server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml server/main/manager-api/src/main/java/xiaozhi/modules/device/entity/DeviceEntity.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/vo/CompanionDeviceVO.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/CompanionDeviceService.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionDeviceServiceImpl.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/dto/DeviceDebugLogSettingDTO.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/CompanionDeviceController.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/entity/CompanionSchemaContractTest.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceServiceImplTest.java
git commit -m "feat(api): add device debug logging switch"
```

### Task 2: Define, sanitize, and truncate structured debug events

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/model/DeviceDebugLogDraft.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/model/DeviceDebugLogEvent.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/dto/DeviceDebugLogIngestDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/vo/DeviceDebugLogHistoryVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/DeviceDebugLogSanitizer.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/DeviceDebugLogSanitizerTest.java`

- [ ] **Step 1: Write failing sanitizer tests**

```java
class DeviceDebugLogSanitizerTest {
    private final DeviceDebugLogSanitizer sanitizer = new DeviceDebugLogSanitizer();

    @Test
    void removesSecretsRecursivelyAndNeverKeepsPromptOrRawAudio() {
        Map<String, Object> details = Map.of(
                "model", "qwen",
                "apiKey", "secret-value",
                "nested", Map.of("Authorization", "Bearer x", "safe", "ok"),
                "systemPrompt", "private prompt",
                "rawAudio", "AAAA");

        Map<String, Object> cleaned = sanitizer.sanitizeDetails(details);

        assertEquals("qwen", cleaned.get("model"));
        assertFalse(cleaned.containsKey("apiKey"));
        assertFalse(cleaned.containsKey("systemPrompt"));
        assertFalse(cleaned.containsKey("rawAudio"));
        assertEquals(Map.of("safe", "ok"), cleaned.get("nested"));
    }

    @Test
    void truncatesLongSummaryAndStringValuesWithAnExplicitMarker() {
        String longText = "x".repeat(5000);
        assertTrue(sanitizer.sanitizeSummary(longText).endsWith("…[已截断]"));
        assertTrue(((String) sanitizer.sanitizeDetails(Map.of("text", longText)).get("text"))
                .endsWith("…[已截断]"));
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=DeviceDebugLogSanitizerTest test
```

Expected: FAIL because the debug event package does not exist.

- [ ] **Step 3: Add stable event records and validation**

Use these records:

```java
package xiaozhi.modules.companion.debug.model;

import java.util.Map;

public record DeviceDebugLogDraft(
        String sessionId,
        String sentenceId,
        String category,
        String eventType,
        String level,
        String summary,
        Map<String, Object> details,
        Long occurredAt,
        Long durationMs) {
}
```

```java
package xiaozhi.modules.companion.debug.model;

import java.util.Map;

public record DeviceDebugLogEvent(
        String cursor,
        String deviceId,
        long occurredAt,
        long receivedAt,
        String sessionId,
        String sentenceId,
        String category,
        String eventType,
        String level,
        String summary,
        Map<String, Object> details,
        Long durationMs) {
}
```

Create `DeviceDebugLogIngestDTO` with this complete contract:

```java
package xiaozhi.modules.companion.debug.dto;

import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogDraft;

@Data
public class DeviceDebugLogIngestDTO {
    @NotBlank
    private String deviceRef;
    private String sessionId;
    private String sentenceId;
    @NotBlank
    @Pattern(regexp = "conversation|model_tool|audio|device")
    private String category;
    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    private String eventType;
    @NotBlank
    @Pattern(regexp = "debug|info|warning|error")
    private String level;
    @NotBlank
    private String summary;
    private Map<String, Object> details;
    private Long occurredAt;
    @Min(0)
    private Long durationMs;

    public DeviceDebugLogDraft toDraft() {
        return new DeviceDebugLogDraft(sessionId, sentenceId, category, eventType,
                level, summary, details, occurredAt, durationMs);
    }
}
```

Create the history response:

```java
package xiaozhi.modules.companion.debug.vo;

import java.util.List;
import xiaozhi.modules.companion.debug.model.DeviceDebugLogEvent;

public record DeviceDebugLogHistoryVO(
        List<DeviceDebugLogEvent> events,
        String lastCursor) {
}
```

- [ ] **Step 4: Implement recursive sanitization**

Normalize each key by splitting camelCase, replacing punctuation with underscores, and lowercasing it. Drop the exact normalized keys `key`, `token`, `secret`, `password`, `authorization`, `credential`, `system_prompt`, `raw_audio`, `audio_base64`, and `messages`; also drop any key that ends with `_key`, `_token`, `_secret`, `_password`, `_authorization`, or `_credential`. This keeps safe names such as `monkeyCount` while removing `apiKey`, `access_token`, and `clientSecret`. Limit summaries to 1000 characters, string detail values to 4000 characters, arrays to 50 entries, maps to 50 entries, and nesting to four levels. Scalars remain unchanged; unsupported objects become their truncated string form.

```java
private static final String TRUNCATED = "…[已截断]";

public String sanitizeSummary(String value) {
    return truncate(value == null ? "" : value, 1000);
}

public Map<String, Object> sanitizeDetails(Map<String, Object> source) {
    Object cleaned = sanitizeValue(source == null ? Map.of() : source, 0);
    return cleaned instanceof Map<?, ?> map
            ? map.entrySet().stream().collect(Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()), Map.Entry::getValue,
                    (left, right) -> left, LinkedHashMap::new))
            : Map.of();
}
```

- [ ] **Step 5: Run the sanitizer test and verify GREEN**

Run the command from Step 2.

Expected: both tests pass.

- [ ] **Step 6: Commit the event contract**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/DeviceDebugLogSanitizerTest.java
git commit -m "feat(api): define sanitized device debug events"
```

### Task 3: Store retained events in Redis Streams

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/DeviceDebugLogStore.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/RedisDeviceDebugLogStore.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/common/redis/RedisKeys.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/RedisDeviceDebugLogStoreTest.java`

- [ ] **Step 1: Write failing store tests around the Redis boundary**

Mock `StringRedisTemplate`, `StreamOperations`, and `ObjectMapper`. Verify append executes a script with the per-device key, JSON payload, `now - 24h` minimum ID, exact maximum length `1000`, and `86400` expiry. Verify history maps records in ascending order and `readAfter` uses a blocking read from the supplied cursor.

```java
@Test
void appendUsesDeviceStreamRetentionAndReturnsRedisCursor() {
    when(redis.execute(any(DefaultRedisScript.class), eq(List.of("device:debug:logs:device-a")),
            any(), any(), any(), any())).thenReturn("1723456789000-0");

    String cursor = store.append("device-a", eventWithoutCursor, 1723456789000L);

    assertEquals("1723456789000-0", cursor);
    verify(redis).execute(any(DefaultRedisScript.class),
            eq(List.of("device:debug:logs:device-a")),
            contains("\"eventType\":\"asr.completed\""),
            eq("1723370389000-0"), eq("1000"), eq("86400"));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=RedisDeviceDebugLogStoreTest test
```

Expected: FAIL because the store does not exist.

- [ ] **Step 3: Add the store interface and Redis key**

```java
public interface DeviceDebugLogStore {
    String append(String deviceId, DeviceDebugLogEvent event, long now);
    List<DeviceDebugLogEvent> history(String deviceId, int limit);
    List<DeviceDebugLogEvent> readAfter(String deviceId, String cursor, Duration block, int limit);
}
```

Add:

```java
public static String getDeviceDebugLogKey(String deviceId) {
    return "device:debug:logs:" + deviceId;
}
```

- [ ] **Step 4: Implement atomic append, trim, and expiry**

Use `StringRedisTemplate` and one Lua script so XADD, time trim, max trim, and expiry stay together:

```java
private static final DefaultRedisScript<String> APPEND_SCRIPT = new DefaultRedisScript<>("""
        local id = redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
        redis.call('XTRIM', KEYS[1], 'MINID', ARGV[2])
        redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[3])
        redis.call('EXPIRE', KEYS[1], ARGV[4])
        return id
        """, String.class);

@Override
public String append(String deviceId, DeviceDebugLogEvent event, long now) {
    String payload = write(event);
    String minimumId = (now - Duration.ofHours(24).toMillis()) + "-0";
    return redis.execute(APPEND_SCRIPT,
            List.of(RedisKeys.getDeviceDebugLogKey(deviceId)),
            payload, minimumId, "1000", String.valueOf(Duration.ofHours(24).toSeconds()));
}
```

Use `opsForStream().range(key, Range.rightOpen(cutoffId, "+"))` for history, where `cutoffId` is the current time minus 24 hours. Take the newest 1000 if necessary and return ascending events with the record ID injected as `cursor`. This prevents a stale entry from being returned even when no later append has triggered trimming. Use `opsForStream().read(StreamReadOptions.empty().count(limit).block(block), StreamOffset.create(key, ReadOffset.from(cursor)))` for continuation reads. Treat missing keys as empty lists.

- [ ] **Step 5: Run the store test and verify GREEN**

Run the command from Step 2.

Expected: append, history, and continuation tests pass.

- [ ] **Step 6: Commit Redis retention**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/common/redis/RedisKeys.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/DeviceDebugLogStore.java server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/RedisDeviceDebugLogStore.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/RedisDeviceDebugLogStoreTest.java
git commit -m "feat(api): retain device debug events in redis"
```

### Task 4: Add owner history, authenticated SSE, and internal ingestion

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/service/DeviceDebugLogService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/controller/CompanionDeviceDebugLogController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/controller/InternalDeviceDebugLogController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug/config/DeviceDebugLogExecutorConfig.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/DeviceDebugLogServiceTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/CompanionDeviceDebugLogControllerTest.java`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug/DeviceDebugLogSecurityConfigTest.java`

- [ ] **Step 1: Write failing service and controller tests**

Cover disabled ingestion, enabled ingestion, foreign-owner rejection, old history remaining readable after the switch is disabled, MAC/device-ID resolution, Redis failure isolation, SSE use of the requested cursor, and internal route protection. `DeviceDebugLogSecurityConfigTest` should construct the Shiro filter bean, read its chain definition map, and assert `/internal/device-debug-logs/**` maps to `server` before the catch-all `/**` mapping.

```java
@Test
void disabledDeviceDoesNotAppendCandidateEvent() {
    DeviceEntity device = ownedDevice(false);
    when(deviceService.selectById("device-a")).thenReturn(device);

    service.ingest("device-a", draft());

    verifyNoInteractions(store);
}

@Test
void enabledDeviceAppendsSanitizedEvent() {
    DeviceEntity device = ownedDevice(true);
    when(deviceService.selectById("device-a")).thenReturn(device);
    when(store.append(eq("device-a"), any(), anyLong())).thenReturn("1-0");

    service.ingest("device-a", draftWithSecret());

    ArgumentCaptor<DeviceDebugLogEvent> saved = ArgumentCaptor.forClass(DeviceDebugLogEvent.class);
    verify(store).append(eq("device-a"), saved.capture(), anyLong());
    assertFalse(saved.getValue().details().containsKey("apiKey"));
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=DeviceDebugLogServiceTest,CompanionDeviceDebugLogControllerTest,DeviceDebugLogSecurityConfigTest test
```

Expected: FAIL because the service and controllers do not exist.

- [ ] **Step 3: Implement device resolution, ownership, and failure isolation**

`DeviceDebugLogService` must resolve `deviceRef` by ID first and then by `deviceService.getDeviceByMacAddress(deviceRef)`. User reads call `requireOwned(userId, deviceId)`. Internal ingestion never accepts a user ID. The public `ingest` method catches store exceptions and logs them without rethrowing:

```java
public void ingest(String deviceRef, DeviceDebugLogDraft draft) {
    try {
        DeviceEntity device = resolveDevice(deviceRef);
        if (device == null || !Integer.valueOf(1).equals(device.getDebugLogEnabled())) return;
        long receivedAt = System.currentTimeMillis();
        DeviceDebugLogEvent event = new DeviceDebugLogEvent(
                null, device.getId(), normalizeOccurredAt(draft.occurredAt(), receivedAt), receivedAt,
                blankToNull(draft.sessionId()), blankToNull(draft.sentenceId()),
                draft.category(), draft.eventType(), draft.level(),
                sanitizer.sanitizeSummary(draft.summary()),
                sanitizer.sanitizeDetails(draft.details()), draft.durationMs());
        store.append(device.getId(), event, receivedAt);
    } catch (RuntimeException exception) {
        log.warn("设备调试事件写入失败: deviceRef={}, reason={}", deviceRef, exception.getMessage());
    }
}
```

History reads do not check whether logging is currently enabled; they only check ownership and return retained records.

- [ ] **Step 4: Add a dedicated virtual-thread SSE executor**

```java
@Configuration
public class DeviceDebugLogExecutorConfig {
    @Bean(name = "deviceDebugLogStreamExecutor", destroyMethod = "close")
    public ExecutorService deviceDebugLogStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

- [ ] **Step 5: Add history and SSE endpoints**

The owner controller exposes:

```java
@GetMapping("/{id}/debug-logs")
@RequiresPermissions("sys:role:normal")
public Result<DeviceDebugLogHistoryVO> history(@PathVariable String id) {
    return new Result<DeviceDebugLogHistoryVO>().ok(service.history(SecurityUser.getUserId(), id));
}

@GetMapping(value = "/{id}/debug-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RequiresPermissions("sys:role:normal")
public SseEmitter stream(@PathVariable String id,
        @RequestParam(defaultValue = "0-0") String after) {
    service.requireOwned(SecurityUser.getUserId(), id);
    SseEmitter emitter = new SseEmitter(0L);
    streamExecutor.execute(() -> service.stream(id, after, emitter));
    return emitter;
}
```

`stream` repeatedly calls `readAfter(deviceId, cursor, Duration.ofSeconds(15), 100)`, sends each record with `id(cursor)`, `name("debug-log")`, and JSON data, and sends a comment heartbeat when a blocking read returns empty. Stop on completion, timeout, or send failure.

- [ ] **Step 6: Add the internal ingestion route and Shiro mapping**

```java
@RestController
@RequestMapping("/internal/device-debug-logs")
@RequiredArgsConstructor
public class InternalDeviceDebugLogController {
    private final DeviceDebugLogService service;

    @PostMapping("/events")
    public Result<Void> ingest(@RequestBody @Valid DeviceDebugLogIngestDTO dto) {
        service.ingest(dto.getDeviceRef(), dto.toDraft());
        return new Result<Void>().ok(null);
    }
}
```

Add this filter entry before `/**`:

```java
filterMap.put("/internal/device-debug-logs/**", "server");
```

- [ ] **Step 7: Run the focused tests and verify GREEN**

Run the command from Step 2.

Expected: all service, controller, ownership, and route-filter tests pass.

- [ ] **Step 8: Commit the end-to-end API channel**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/debug server/main/manager-api/src/main/java/xiaozhi/modules/security/config/ShiroConfig.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/debug
git commit -m "feat(api): stream owner-scoped device debug logs"
```

### Task 5: Record manager-api device command outcomes

**Files:**
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionDeviceServiceImpl.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceServiceImplTest.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceBindingConcurrencyTest.java`
- Modify: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceActivationConcurrencyTest.java`

- [ ] **Step 1: Add failing command-event tests**

Update every direct `CompanionDeviceServiceImpl` construction in the three listed test classes to include a mocked `DeviceDebugLogService`. Assert successful commands create `command.started` and `command.completed`, explicit failure creates `command.failed`, and a debug-store exception does not replace the original command result.

```java
@Test
void successfulVolumeCommandRecordsStartAndCompletion() {
    DeviceDebugLogService debugLogs = mock(DeviceDebugLogService.class);
    DeviceService deviceService = mock(DeviceService.class);
    when(deviceService.selectById("device-a")).thenReturn(ownedDevice());
    when(deviceService.callDeviceTool("device-a", "self.audio_speaker.set_volume", Map.of("volume", 35)))
            .thenReturn(Map.of("success", true));
    CompanionDeviceService service = new CompanionDeviceServiceImpl(
            deviceService, mock(CompanionProfileService.class), debugLogs);

    service.command(7L, "device-a", new CompanionDeviceCommandDTO("volume", 35));

    verify(debugLogs).ingest(eq("device-a"), argThat(event -> event.eventType().equals("command.started")));
    verify(debugLogs).ingest(eq("device-a"), argThat(event -> event.eventType().equals("command.completed")));
}
```

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest=CompanionDeviceServiceImplTest test
```

Expected: FAIL because the service does not emit events and its constructor has no debug service.

- [ ] **Step 3: Instrument command lifecycle without changing command semantics**

Record a start timestamp after ownership succeeds. Emit safe details containing only command name and numeric target value. Emit completion with elapsed milliseconds after a confirmed result. Emit failure before throwing existing `DEVICE_OFFLINE` or `DEVICE_COMMAND_FAILED` exceptions. Do not include gateway credentials or the full raw exception object. Route every event through a private `emitCommandEvent` helper that catches `RuntimeException`, so even a misconfigured or mocked debug service cannot change command behavior.

```java
long startedAt = System.currentTimeMillis();
emitCommandEvent(deviceId, commandEvent("command.started", "info", dto, null, null));
try {
    Object result = deviceService.callDeviceTool(deviceId, toolName, Map.of(argumentName, dto.getValue()));
    validateCommandResult(result);
    emitCommandEvent(deviceId, commandEvent(
            "command.completed", "info", dto, "设备命令执行完成",
            System.currentTimeMillis() - startedAt));
    return result;
} catch (RenException exception) {
    emitCommandEvent(deviceId, commandEvent(
            "command.failed", "error", dto, exception.getMessage(),
            System.currentTimeMillis() - startedAt));
    throw exception;
} catch (RuntimeException exception) {
    emitCommandEvent(deviceId, commandEvent(
            "command.failed", "error", dto, "实时控制通道不可用",
            System.currentTimeMillis() - startedAt));
    throw new RenException(ErrorCode.DEVICE_OFFLINE, exception);
}
```

Add the failure-isolating helper:

```java
private void emitCommandEvent(String deviceId, DeviceDebugLogDraft event) {
    try {
        debugLogService.ingest(deviceId, event);
    } catch (RuntimeException exception) {
        log.debug("设备命令调试事件已丢弃: deviceId={}, reason={}", deviceId, exception.getMessage());
    }
}
```

- [ ] **Step 4: Run the service test and verify GREEN**

Run the command from Step 2.

Expected: existing command behavior and new event assertions all pass.

- [ ] **Step 5: Commit manager-side device events**

```bash
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionDeviceServiceImpl.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceServiceImplTest.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceBindingConcurrencyTest.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/CompanionDeviceActivationConcurrencyTest.java
git commit -m "feat(api): trace device command outcomes"
```

### Task 6: Add the non-blocking xiaozhi-server event reporter

**Files:**
- Create: `server/main/xiaozhi-server/core/debug_events.py`
- Modify: `server/main/xiaozhi-server/config/manage_api_client.py`
- Create: `server/main/xiaozhi-server/tests/test_debug_events.py`

- [ ] **Step 1: Write failing reporter tests**

```python
def test_emit_never_blocks_when_queue_is_full():
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append, queue_size=1)
    reporter._queue.put_nowait({"occupied": True})

    assert reporter.emit("audio", "asr.started", "info", "开始识别") is False


def test_worker_sends_stable_payload_without_raw_audio_or_thoughts():
    sent = []
    reporter = DebugEventReporter("device-a", "session-a", sender=sent.append)
    reporter.emit("audio", "asr.completed", "info", "语音识别完成",
                  details={"textLength": 2}, sentence_id="sentence-a", duration_ms=12)
    reporter.flush_for_test()

    assert sent == [{
        "deviceRef": "device-a", "sessionId": "session-a", "sentenceId": "sentence-a",
        "category": "audio", "eventType": "asr.completed", "level": "info",
        "summary": "语音识别完成", "details": {"textLength": 2},
        "occurredAt": sent[0]["occurredAt"], "durationMs": 12,
    }]
```

Also test that sender failures are swallowed and the next queued event is still processed.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_debug_events.py
```

Expected: FAIL because `core.debug_events` does not exist.

- [ ] **Step 3: Add the manager client call**

```python
async def report_debug_event(payload: Dict) -> Optional[Dict]:
    if not ManageApiClient._instance:
        return None
    return await ManageApiClient._instance._execute_async_request(
        "POST", "/internal/device-debug-logs/events", json=payload
    )
```

- [ ] **Step 4: Implement a bounded per-connection reporter**

`DebugEventReporter` uses `queue.Queue(maxsize=256)`, one daemon worker thread, and `put_nowait`. Its default sender runs `asyncio.run(report_debug_event(payload))`. `emit` returns `False` when the queue is full or reporter is stopped. `close` sets the stop flag without waiting on network I/O. It permits only the four known categories and four known levels, converts timestamps to milliseconds, and never generates `thinking`, `reasoning`, raw audio, prompt, or messages fields.

```python
def emit(self, category, event_type, level, summary, *, details=None,
         sentence_id=None, duration_ms=None, occurred_at=None):
    payload = {
        "deviceRef": self.device_ref,
        "sessionId": self.session_id,
        "sentenceId": sentence_id,
        "category": category,
        "eventType": event_type,
        "level": level,
        "summary": str(summary),
        "details": details or {},
        "occurredAt": occurred_at or int(time.time() * 1000),
        "durationMs": duration_ms,
    }
    try:
        self._queue.put_nowait(payload)
        return True
    except queue.Full:
        return False
```

- [ ] **Step 5: Run reporter tests and verify GREEN**

Run the command from Step 2.

Expected: reporter queue, payload, close, and failure-isolation tests pass.

- [ ] **Step 6: Commit the reporter**

```bash
git add server/main/xiaozhi-server/core/debug_events.py server/main/xiaozhi-server/config/manage_api_client.py server/main/xiaozhi-server/tests/test_debug_events.py
git commit -m "feat(server): add nonblocking debug event reporter"
```

### Task 7: Instrument connection, VAD, ASR, LLM, tools, and TTS

**Files:**
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/handle/textHandler/pingMessageHandler.py`
- Modify: `server/main/xiaozhi-server/core/providers/vad/silero.py`
- Modify: `server/main/xiaozhi-server/core/providers/asr/base.py`
- Modify: `server/main/xiaozhi-server/core/providers/tts/base.py`
- Modify: `server/main/xiaozhi-server/tests/test_companion_conversation.py`
- Create: `server/main/xiaozhi-server/tests/test_debug_event_instrumentation.py`

- [ ] **Step 1: Write failing lifecycle instrumentation tests**

Use a fake reporter that captures calls. Cover connection opened/closed/failed, WebSocket heartbeat sampled no more than once per minute, VAD voice transition only when state changes, ASR started/completed/failed, transcript publication, LLM started/completed/failed with model and duration, final assistant response, tool called/completed/failed, and TTS started/completed. Assert no event type or details contain `thinking`, `reasoning`, `systemPrompt`, `messages`, or audio bytes.

```python
def test_asr_completion_records_text_and_duration_without_audio():
    conn = fake_connection()
    conn.debug_events = CapturingReporter()
    provider = FakeAsrProvider(result=("你好", None))

    asyncio.run(provider.handle_voice_stop(conn, [b"pcm-frame"]))

    audio_completed = next(event for event in conn.debug_events.events
                           if event["event_type"] == "asr.completed")
    transcript = next(event for event in conn.debug_events.events
                      if event["event_type"] == "conversation.user")
    assert audio_completed["category"] == "audio"
    assert transcript["details"] == {"text": "你好"}
    assert isinstance(audio_completed["duration_ms"], int)
    assert b"pcm-frame" not in repr(conn.debug_events.events)
```

- [ ] **Step 2: Run the focused Python tests and verify RED**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_debug_event_instrumentation.py tests/test_companion_conversation.py
```

Expected: FAIL because connections have no reporter and lifecycle calls are absent.

- [ ] **Step 3: Attach and close the reporter with the connection**

Initialize `self.debug_events = None` and `self._last_debug_heartbeat_at = 0.0`. After `device-id` is read and authentication succeeds, create `DebugEventReporter(self.device_id, self.session_id)` and emit `connection.opened`. Emit `connection.failed` in the authenticated connection exception path with a short error class and message. In `close`, emit `connection.closed` before calling `debug_events.close()`.

Add this safe helper to `ConnectionHandler` so instrumentation never raises:

```python
def emit_debug_event(self, category, event_type, level, summary, **kwargs):
    reporter = getattr(self, "debug_events", None)
    if reporter is None:
        return False
    try:
        return reporter.emit(category, event_type, level, summary, **kwargs)
    except Exception as error:
        self.logger.bind(tag=TAG).debug(f"调试事件已丢弃: {error}")
        return False
```

- [ ] **Step 4: Instrument sampled heartbeat, VAD, and ASR transitions**

In `PingMessageHandler`, after a successful pong, emit `heartbeat.sampled` in category `device` only when at least 60 seconds have elapsed since `conn._last_debug_heartbeat_at`. Update the timestamp only after accepting the sample. This records liveness without copying every heartbeat.

In Silero VAD, compare the previous state before assigning `conn.last_is_voice`. Emit `vad.voice_started` only on false-to-true and `vad.voice_stopped` only on true-to-false. In ASR, record monotonic start and emit `asr.started`. On success emit `asr.completed` in category `audio` with language, emotion, speaker, text length, and duration, then emit `conversation.user` in category `conversation` with the final text and speaker metadata. Emit `asr.failed` in category `audio` on exceptions. Never attach PCM, WAV, or Opus data.

- [ ] **Step 5: Instrument LLM response and tools**

At the top-level `chat` call, emit `llm.started` before the provider request with only the selected LLM resource name. Emit `llm.failed` in the existing exception path. After response processing, emit `llm.completed` with duration and output character count, then emit `conversation.assistant` in category `conversation` with the same final text that is stored for TTS. Around every tool call emit `tool.called`, followed by `tool.completed` or `tool.failed`, with sanitized-by-server name, JSON arguments, compact result, and duration.

Use a top-level monotonic timer and avoid emitting assistant events from recursive tool calls until a user-visible response exists:

```python
llm_started_at = time.monotonic()
self.emit_debug_event("model_tool", "llm.started", "info", "模型开始处理",
                      sentence_id=current_sentence_id,
                      details={"model": self.config.get("selected_module", {}).get("LLM")})
```

- [ ] **Step 6: Instrument TTS session boundaries**

Add `_debug_tts_started_at = {}` to `TTSProviderBase`. On `SentenceType.FIRST`, store monotonic time and emit `tts.started` with sentence ID and text character count when known. On `SentenceType.LAST`, emit `tts.completed` with elapsed milliseconds. Existing TTS exceptions emit `tts.failed`. Do not include audio frames or generated files.

- [ ] **Step 7: Run instrumentation and regression tests and verify GREEN**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_debug_events.py tests/test_debug_event_instrumentation.py tests/test_companion_conversation.py tests/test_fun_local_asr.py tests/test_tts_expression.py
```

Expected: all selected tests pass, including the no-thought/no-audio assertions.

- [ ] **Step 8: Commit runtime instrumentation**

```bash
git add server/main/xiaozhi-server/core/connection.py server/main/xiaozhi-server/core/handle/textHandler/pingMessageHandler.py server/main/xiaozhi-server/core/providers/vad/silero.py server/main/xiaozhi-server/core/providers/asr/base.py server/main/xiaozhi-server/core/providers/tts/base.py server/main/xiaozhi-server/tests/test_companion_conversation.py server/main/xiaozhi-server/tests/test_debug_event_instrumentation.py
git commit -m "feat(server): trace device conversation lifecycle"
```

### Task 8: Add typed history and authenticated SSE to the console API

**Files:**
- Modify: `server/main/companion-console/src/api/http.ts`
- Modify: `server/main/companion-console/src/api/devices.ts`
- Modify: `server/main/companion-console/src/api/devices.test.ts`
- Create: `server/main/companion-console/src/api/deviceDebugLogs.ts`
- Create: `server/main/companion-console/src/api/deviceDebugLogs.test.ts`

- [ ] **Step 1: Write failing parser and API tests**

Cover the new `debugLogEnabled` device field, settings URL encoding, strict history parsing, SSE split across chunks, comment heartbeat ignoring, event ID propagation, bearer header use, abort handling, and malformed event rejection.

```ts
it('parses split SSE chunks and keeps the server cursor', async () => {
  const chunks = ['id: 10-0\nevent: debug-log\nda', 'ta: {"cursor":"10-0","deviceId":"d1",',
    '"occurredAt":1,"receivedAt":2,"category":"conversation","eventType":"conversation.user","level":"info","summary":"你好","details":{}}\n\n']
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse(chunks)))
  const events: DebugLogEvent[] = []

  await streamDeviceDebugLogs('d1', '0-0', { signal: new AbortController().signal, onEvent: events.push })

  expect(events.map((event) => event.cursor)).toEqual(['10-0'])
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/companion/devices/d1/debug-logs/stream?after=0-0'),
    expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer token-a' }) }))
})
```

- [ ] **Step 2: Run API tests and verify RED**

Run:

```bash
cd server/main/companion-console
npm test -- src/api/devices.test.ts src/api/deviceDebugLogs.test.ts
```

Expected: FAIL because the device field and debug-log API do not exist.

- [ ] **Step 3: Expose the current authorization header safely**

Add to `http.ts`:

```ts
export function currentAuthorizationHeader() {
  const token = authBridge.getToken()
  return token ? `Bearer ${token}` : null
}

export function apiBaseUrl() {
  return import.meta.env.VITE_API_BASE_URL || '/xiaozhi'
}
```

Do not expose the token in query parameters.

- [ ] **Step 4: Extend the device contract and switch API**

Require `debugLogEnabled: boolean` in `CompanionDevice`, validate it in `parseDevice`, and add:

```ts
export async function setDeviceDebugLogging(id: string, enabled: boolean, options?: RequestOptions) {
  unwrap(await http.put<ApiResult<null>>(
    `/companion/devices/${encodedId(id)}/debug-logs/settings`, { enabled }, requestConfig(options),
  ))
}
```

- [ ] **Step 5: Implement strict history and SSE parsing**

Define `DebugLogCategory`, `DebugLogLevel`, `DebugLogEvent`, and `DebugLogHistory`. `getDeviceDebugLogHistory` uses Axios and validates every field. `streamDeviceDebugLogs` uses `fetch`, `Accept: text/event-stream`, the current bearer header, `TextDecoder`, and a buffer split on blank SSE lines. Ignore comment blocks. Parse only `event: debug-log`. If the JSON cursor is absent, use the SSE `id` line. Reject non-2xx responses before reading.

```ts
export async function streamDeviceDebugLogs(
  deviceId: string,
  after: string,
  options: { signal: AbortSignal; onOpen?: () => void; onEvent: (event: DebugLogEvent) => void },
) {
  const authorization = currentAuthorizationHeader()
  const response = await fetch(
    `${apiBaseUrl()}/companion/devices/${encodeURIComponent(deviceId)}/debug-logs/stream?after=${encodeURIComponent(after)}`,
    { signal: options.signal, headers: {
      Accept: 'text/event-stream', ...(authorization ? { Authorization: authorization } : {}),
    } },
  )
  if (!response.ok || !response.body) throw new Error(`实时日志连接失败 (${response.status})`)
  options.onOpen?.()
  await consumeSse(response.body, options.onEvent)
}
```

- [ ] **Step 6: Run API tests and verify GREEN**

Run the command from Step 2.

Expected: all device and debug-log API tests pass.

- [ ] **Step 7: Commit the console data layer**

```bash
git add server/main/companion-console/src/api/http.ts server/main/companion-console/src/api/devices.ts server/main/companion-console/src/api/devices.test.ts server/main/companion-console/src/api/deviceDebugLogs.ts server/main/companion-console/src/api/deviceDebugLogs.test.ts
git commit -m "feat(console): consume retained device debug streams"
```

### Task 9: Build the multi-tab log panel and integrate device detail

**Files:**
- Create: `server/main/companion-console/src/pages/devices/DeviceDebugLogPanel.tsx`
- Create: `server/main/companion-console/src/pages/devices/DeviceDebugLogPanel.test.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.tsx`
- Modify: `server/main/companion-console/src/pages/devices/DeviceDetailPage.test.tsx`
- Modify: `server/main/companion-console/src/styles.css`

- [ ] **Step 1: Write failing panel tests**

Mock history and stream APIs. Cover history-first rendering, real-time append, duplicate cursor removal, five tab filters, switch update, pause-scroll retaining events, local clear not calling a delete API, reconnection from the last cursor, disabled logging retaining old history, and the unavailable-thought notice.

```tsx
it('loads history, appends live events, and deduplicates by cursor', async () => {
  vi.mocked(getDeviceDebugLogHistory).mockResolvedValue({
    lastCursor: '1-0', events: [event('1-0', 'conversation', 'conversation.user', '你好')],
  })
  vi.mocked(streamDeviceDebugLogs).mockImplementation(async (_id, after, options) => {
    expect(after).toBe('1-0')
    options.onOpen?.()
    options.onEvent(event('1-0', 'conversation', 'conversation.user', '你好'))
    options.onEvent(event('2-0', 'conversation', 'conversation.assistant', '你好呀'))
  })

  render(<DeviceDebugLogPanel deviceId="d1" enabled onEnabledChange={vi.fn()} />)

  expect(await screen.findByText('你好')).toBeVisible()
  expect(await screen.findByText('你好呀')).toBeVisible()
  expect(screen.getAllByText('你好')).toHaveLength(1)
})
```

- [ ] **Step 2: Run panel tests and verify RED**

Run:

```bash
cd server/main/companion-console
npm test -- src/pages/devices/DeviceDebugLogPanel.test.tsx src/pages/devices/DeviceDetailPage.test.tsx
```

Expected: FAIL because the panel does not exist and the detail page does not render it.

- [ ] **Step 3: Implement history/live state and reconnection**

The component keeps `events`, `cursor`, `connectionState`, `activeTab`, `autoScroll`, and `toggleLoading`. Load history once per device ID. Start SSE only after history resolves. Merge through a `Map<string, DebugLogEvent>`, sort by `(receivedAt, cursor)`, and cap browser memory at 1000. On non-abort failure set `reconnecting`, wait with capped backoff `1s, 2s, 5s`, and reconnect from the last cursor. Abort and reset when device ID changes or the component unmounts.

- [ ] **Step 4: Implement panel controls and tabs**

Use an Ant `Card` spanning the full detail grid. The header contains `Switch`, connection `Badge`, an auto-scroll toggle button, and `清空当前视图`. Tabs are `全部`, `对话`, `模型与工具`, `音频链路`, and `设备事件`. Render time, level tag, event label, summary, duration, and expandable JSON details. Empty tabs show a compact empty state.

Always render this notice inside the model/tool tab:

```tsx
<Alert
  type="info"
  showIcon
  message="模型内部思考不可用"
  description="当前系统只展示模型处理阶段、最终回复、耗时和工具调用，不展示或推测模型内部思考。"
/>
```

`清空当前视图` calls only `setEvents([])`. It must not call the backend. Turning logging off calls `setDeviceDebugLogging(deviceId, false)` and keeps retained events visible.

- [ ] **Step 5: Integrate the panel into DeviceDetailPage**

Add `debugLogEnabled` to the page fixture. Render the panel before the danger card:

```tsx
<DeviceDebugLogPanel
  deviceId={device.id}
  enabled={device.debugLogEnabled}
  onEnabledChange={(enabled) => {
    setDevice((current) => current?.id === device.id
      ? { ...current, debugLogEnabled: enabled }
      : current)
  }}
/>
```

The existing 30-second device refresh remains authoritative and may update the switch if it changed elsewhere.

- [ ] **Step 6: Add focused responsive styles**

Add styles for `.debug-log-card`, `.debug-log-toolbar`, `.debug-log-viewport`, `.debug-log-row`, `.debug-log-meta`, `.debug-log-summary`, and `.debug-log-details`. Use a fixed viewport height around `420px`, `overflow-y: auto`, monospace only for details, wrapping summaries, and a one-column compact row below 720 px. Set `.debug-log-card { grid-column: 1 / -1; }`.

- [ ] **Step 7: Run component tests and verify GREEN**

Run the command from Step 2.

Expected: panel behavior and existing device-detail behavior pass.

- [ ] **Step 8: Commit the device detail panel**

```bash
git add server/main/companion-console/src/pages/devices/DeviceDebugLogPanel.tsx server/main/companion-console/src/pages/devices/DeviceDebugLogPanel.test.tsx server/main/companion-console/src/pages/devices/DeviceDetailPage.tsx server/main/companion-console/src/pages/devices/DeviceDetailPage.test.tsx server/main/companion-console/src/styles.css
git commit -m "feat(console): add realtime device debug panel"
```

### Task 10: Run end-to-end regression verification

**Files:**
- Modify only files required to fix failures discovered by the commands below.

- [ ] **Step 1: Run the full companion-console test suite**

Run:

```bash
cd server/main/companion-console
npm test
```

Expected: all Vitest tests pass.

- [ ] **Step 2: Run console lint, type checking, and production build**

Run:

```bash
cd server/main/companion-console
npm run lint
npm run build
```

Expected: ESLint passes; TypeScript, Vite build, and build verification pass.

- [ ] **Step 3: Run manager-api focused and full tests**

Run:

```bash
cd server/main/manager-api
mvn -DskipTests=false -Dtest='xiaozhi.modules.companion.**' test
mvn -DskipTests=false test
```

Expected: companion tests and the full Maven suite pass. If the repository's configured test pattern does not accept `**`, run the named new test classes plus `CompanionDeviceServiceImplTest` and `CompanionSchemaContractTest`, then run the full suite.

- [ ] **Step 4: Run xiaozhi-server focused and full tests**

Run:

```bash
cd server/main/xiaozhi-server
pytest -q tests/test_debug_events.py tests/test_debug_event_instrumentation.py tests/test_companion_conversation.py tests/test_fun_local_asr.py tests/test_tts_expression.py
pytest -q
```

Expected: focused instrumentation tests and the full Python suite pass.

- [ ] **Step 5: Verify the migration pair and workspace diff**

Run:

```bash
git diff --check
git status --short
git log --oneline -12
```

Expected: no whitespace errors; only intended feature files remain changed; the user's existing `mqtt-gateway/start-local.sh` and `mqtt-gateway/tests/` changes are untouched.

- [ ] **Step 6: Commit any verification-only fixes**

If verification required changes, stage only the files changed for this feature and commit:

```bash
git commit -m "fix: complete device debug log verification"
```

If no fixes were required, do not create an empty commit.
