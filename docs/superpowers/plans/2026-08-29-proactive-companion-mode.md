# Proactive Companion Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Add an administrator-configurable device conversation mode and a global proactive-companion planner that filters noise, respects real user activity, retrieves bounded memory, and produces cancellable short voice prompts.

**Architecture:** Persist `turn_based` or `proactive` on each device. Persist one global proactive planner prompt in system settings, merge it with fixed server rules and Agent guidance, and expose the resulting snapshot to each Python connection. A per-connection `CompanionLoop` owns idle timers, planner cancellation, activity epochs, bounded memory retrieval, and proactive TTS delivery.

**Tech Stack:** Java Spring Boot/MyBatis-Plus/Liquibase, React/TypeScript/Ant Design, Python asyncio and existing LLM/Memory/TTS/VAD providers, ESP-IDF C++ audio state machine.

---

### Task 1: Persist device mode and global planner prompt

**Files:**
- Create: `server/main/manager-api/src/main/resources/db/changelog/202608290900.sql`
- Modify: `server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/device/entity/DeviceEntity.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/dto/AdminSystemSettingsSaveDTO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/vo/AdminSystemSettingsVO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/AdminSystemSettingsServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/CompanionConfigServiceImpl.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/config/service/impl/ConfigServiceImplTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/impl/AdminSystemSettingsServiceImplTest.java`

- [ ] **Step 1: Write failing persistence and config tests.** Assert old rows resolve to `turn_based`, system settings round-trip `proactivePlannerPrompt`, and `CompanionConfigService.build` returns device mode plus the global prompt with a built-in fallback.

- [ ] **Step 2: Run the focused Java tests and confirm failure.**

Run from `server/main/manager-api`:

```bash
mvn -Dtest=ConfigServiceImplTest,AdminSystemSettingsServiceImplTest test
```

Expected: compilation or assertion failures for the missing fields and config mapping.

- [ ] **Step 3: Add the migration and Java fields.** Add a nullable `VARCHAR(32)` `companion_mode` to `ai_device`, backfill `turn_based`, and register the migration. Add `companionMode` to `DeviceEntity`. Add optional `proactivePlannerPrompt` to system settings DTO/VO and persist it through the existing system parameter mechanism under a dedicated key. Preserve the built-in default when the stored value is blank.

- [ ] **Step 4: Merge the values into the runtime companion config.** Add `mode` and `proactive_planner_prompt` to `CompanionConfigServiceImpl.build`, with device mode taking precedence over defaults. Ensure invalid device mode normalizes to `turn_based`.

- [ ] **Step 5: Run the focused Java tests and commit.**

```bash
mvn -Dtest=ConfigServiceImplTest,AdminSystemSettingsServiceImplTest test
git add server/main/manager-api/src/main/resources/db/changelog/202608290900.sql server/main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml server/main/manager-api/src/main/java/xiaozhi/modules/device/entity/DeviceEntity.java server/main/manager-api/src/main/java/xiaozhi/modules/companion server/main/manager-api/src/test/java/xiaozhi/modules/config/service/impl/ConfigServiceImplTest.java server/main/manager-api/src/test/java/xiaozhi/modules/companion/service/impl/AdminSystemSettingsServiceImplTest.java
git commit -m "feat: persist proactive companion settings"
```

### Task 2: Add admin API and console controls

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/dto/AdminCompanionDeviceModeUpdateDTO.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/controller/AdminCompanionController.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/AdminCompanionDeviceService.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/service/impl/AdminCompanionDeviceServiceImpl.java`
- Modify: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/vo/CompanionDeviceVO.java`
- Modify: `server/main/companion-console/src/api/admin.ts`
- Modify: `server/main/companion-console/src/pages/admin/DeviceFleetPage.tsx`
- Modify: `server/main/companion-console/src/api/devices.ts`
- Modify: `server/main/companion-console/src/pages/admin/SystemSettingsPage.tsx`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/controller/AdminCompanionControllerTest.java`
- Test: `server/main/companion-console/src/pages/admin/DeviceFleetPage.test.tsx`
- Test: `server/main/companion-console/src/pages/admin/SystemSettingsPage.test.tsx`

- [ ] **Step 1: Add failing API and UI tests.** Cover `PUT /admin/companion/devices/{id}/mode`, invalid mode rejection, audit metadata, device row display/edit, and system settings prompt load/save without overwriting unrelated fields.

- [ ] **Step 2: Implement the dedicated mode endpoint.** Validate `turn_based` and `proactive`, update only `ai_device.companion_mode`, return a readable error for missing devices, and record the old/new values in the existing audit service.

- [ ] **Step 3: Project mode through device responses.** Add `companionMode` to the admin list DTO projection and companion device VO. Keep existing alias update behavior unchanged.

- [ ] **Step 4: Add the console controls.** Add a mode column and edit control to `DeviceFleetPage`. Add a global prompt textarea to `SystemSettingsPage` using the existing form submit flow. Show saved value, preserve draft on failed saves, and display the built-in-default state when empty.

- [ ] **Step 5: Run Java and console tests and commit.**

```bash
mvn -Dtest=AdminCompanionControllerTest test
cd server/main/companion-console && npm test -- --run src/pages/admin/DeviceFleetPage.test.tsx src/pages/admin/SystemSettingsPage.test.tsx
git add server/main/manager-api/src/main/java/xiaozhi/modules/companion server/main/companion-console/src/api/admin.ts server/main/companion-console/src/api/devices.ts server/main/companion-console/src/pages/admin
git commit -m "feat: expose proactive mode in admin console"
```

### Task 3: Implement prompt layering and planner contract

**Files:**
- Create: `server/main/xiaozhi-server/core/companion/proactive_planner.py`
- Modify: `server/main/xiaozhi-server/core/utils/prompt_manager.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/config.yaml`
- Test: `server/main/xiaozhi-server/tests/test_proactive_planner.py`
- Test: `server/main/xiaozhi-server/tests/test_prompt_manager.py`

- [ ] **Step 1: Write failing planner tests.** Test fixed rules plus global prompt plus Agent guidance ordering, strict `speak`/`silent` parsing, 80-character rejection, and prompt injection text inside memory being treated as data.

- [ ] **Step 2: Implement the planner module.** Define a small `ProactivePlanner` boundary that accepts recent turns, proactive history, memory candidates, idle duration, and prompt layers. It calls the configured LLM with no tools and parses `{action,text,emotion,reason_code}`. Invalid output becomes `silent`.

- [ ] **Step 3: Fix prompt layering.** Change `PromptManager` so companion persona content augments the Agent prompt instead of replacing it. Add the global planner prompt only to planner requests. Keep the existing reply protocol for ordinary companion responses.

- [ ] **Step 4: Add default settings.** Put the built-in planner prompt and initial idle/backoff/character defaults in `config.yaml`; pass the API-provided global prompt through the per-connection config snapshot.

- [ ] **Step 5: Run Python tests and commit.**

```bash
cd server/main/xiaozhi-server && pytest -q tests/test_proactive_planner.py tests/test_prompt_manager.py
git add server/main/xiaozhi-server/core/companion/proactive_planner.py server/main/xiaozhi-server/core/utils/prompt_manager.py server/main/xiaozhi-server/core/connection.py server/main/xiaozhi-server/config.yaml server/main/xiaozhi-server/tests/test_proactive_planner.py server/main/xiaozhi-server/tests/test_prompt_manager.py
git commit -m "feat: add proactive planner contract"
```

### Task 4: Add connection-level activity filtering and proactive loop

**Files:**
- Create: `server/main/xiaozhi-server/core/companion/companion_loop.py`
- Modify: `server/main/xiaozhi-server/core/connection.py`
- Modify: `server/main/xiaozhi-server/core/handle/receiveAudioHandle.py`
- Modify: `server/main/xiaozhi-server/core/handle/sendAudioHandle.py`
- Modify: `server/main/xiaozhi-server/core/providers/vad/silero.py`
- Modify: `server/main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py`
- Test: `server/main/xiaozhi-server/tests/test_companion_loop.py`
- Test: `server/main/xiaozhi-server/tests/test_audio_activity.py`

- [ ] **Step 1: Write failing activity and cancellation tests.** Cover noise not refreshing activity, short human vocalizations refreshing activity without starting chat, user activity cancelling planner/TTS, planner/playback mutual exclusion, activity epoch checks, and exponential backoff.

- [ ] **Step 2: Implement confirmed activity tracking.** Add a separate activity signal from VAD state transitions and minimum duration. Refresh `last_confirmed_user_activity` for confirmed human sound even when ASR is empty. Keep ordinary `startToChat` gated on non-empty recognized text. Preserve the existing manual mode behavior.

- [ ] **Step 3: Implement `CompanionLoop`.** Start it only when the connection snapshot has `companion.enabled` and `mode=proactive`. Keep the connection alive, arm idle timers, cancel on activity, call `ProactivePlanner`, check the activity epoch before TTS, and write successful proactive messages with `source=proactive`.

- [ ] **Step 4: Integrate lifecycle and playback.** Cancel the loop from connection close and abort paths. Make proactive TTS use the existing sentence and queue cleanup. Bypass ordinary no-voice goodbye only for proactive mode. Refuse proactive mode when the device has not negotiated AEC/realtime capability.

- [ ] **Step 5: Run Python tests and commit.**

```bash
cd server/main/xiaozhi-server && pytest -q tests/test_companion_loop.py tests/test_audio_activity.py tests/test_audio_barge_in.py
git add server/main/xiaozhi-server/core/companion/companion_loop.py server/main/xiaozhi-server/core/connection.py server/main/xiaozhi-server/core/handle server/main/xiaozhi-server/core/providers/vad/silero.py server/main/xiaozhi-server/tests/test_companion_loop.py server/main/xiaozhi-server/tests/test_audio_activity.py
git commit -m "feat: run cancellable proactive companion loop"
```

### Task 5: Firmware capability, integration verification, and documentation

**Files:**
- Modify: `firmware/main/application.cc`
- Modify: `firmware/main/application.h`
- Modify: `firmware/main/audio/audio_service.cc`
- Modify: `firmware/main/audio/audio_service.h`
- Modify: `docs/dynamic-device-wake-word.md` only if protocol references need updating
- Modify: `docs/project-chain-roadmap.md`
- Test: `firmware/tests` existing board/audio tests
- Test: `mqtt-gateway/tests` existing bridge tests

- [ ] **Step 1: Add firmware capability reporting and pre-roll hooks.** Keep board-specific differences in board config, expose whether realtime/AEC is available, and preserve the existing auto-stop queue drain for `turn_based`.

- [ ] **Step 2: Verify gateway forwarding.** Ensure mode/config snapshots and proactive stop/abort events traverse MQTT without changing device credentials or NVS.

- [ ] **Step 3: Run the full relevant suites.**

```bash
cd server/main/xiaozhi-server && pytest -q
cd ../../mqtt-gateway && npm test
cd ../../firmware && pytest -q tests
cd ../companion-console && npm test -- --run
```

- [ ] **Step 4: Perform real-device acceptance.** Verify turn-based compatibility, proactive connection keepalive, keyboard/table noise, speaker echo, cough/嗯 activity, planning cancellation, short proactive TTS, user barge-in, restart recovery, and unsupported-AEC fallback. Record board type, firmware version, Agent version, mode, VAD transitions, planner decision, ASR result, and TTS state without storing raw audio or secrets.

- [ ] **Step 5: Update chain documentation and commit.** Document the new device setting, global planner prompt, memory bounds, and acceptance evidence.

## Self-review checklist

The plan covers the approved design requirements: device-level mode, global planner prompt, fixed prompt precedence, bounded memory retrieval, short human sounds as activity, cancellable LLM planning, proactive TTS, connection keepalive, unsupported AEC fallback, admin UI, audit, and cross-layer verification. No task relies on a free-form placeholder; each code task names its files, tests, command, and expected outcome.
