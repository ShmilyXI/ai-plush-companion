# Flutter AI Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Chinese-first Flutter Android/iOS consumer App that supports account access, role and device management, text and voice conversations, full-screen realtime calls, durable history, shared role memory, and SoftAP device provisioning while preserving the existing hardware and management contracts.

**Architecture:** The App talks directly to manager-api over HTTPS, receives a short-lived runtime token, and connects directly to the Python public WebSocket for conversation audio. The existing device portal remains the local provisioning surface inside a restricted WebView; MQTT remains hardware-only. Backend changes are split into authentication, profile, durable conversation, and canonical-memory slices before the Flutter client is allowed to claim the corresponding behavior.

**Tech Stack:** Flutter 3.35/Dart 3.9, Riverpod, go_router, Dio, web_socket_channel, record, just_audio, audio_service, audio_session, webview_flutter, permission_handler, app_settings, flutter_secure_storage, shared_preferences, Spring Boot/MyBatis-Plus, Liquibase, Redis, Python asyncio/aiohttp, JUnit 5 and pytest.

---

## Design reference and current evidence

Implement against [the approved design](/Users/xiaox/WorkShop/ai-plush-companion-public/docs/superpowers/specs/2026-08-29-flutter-ai-companion-design.md). The repository has no Flutter project. `server/main/manager-mobile` is an existing UniApp management client and must remain intact. `server/main/companion-web` is a useful protocol reference for `web.session.start`, PCM streaming, `input.audio.commit`, interruption and TTS event pairing, but it is not the consumer App.

The current public conversation API can create a new session and read history only when the caller already knows a conversation ID. The current Redis history has a 24-hour lifetime and a 50-item limit. The current profile update saves a draft and snapshot but does not atomically publish and activate; the editable Agent surface also lacks several voice parameters and avatar metadata. The current memory controller is device-scoped, while public conversation memory is conversation-scoped. The current authentication controller requires the legacy graph captcha and has no email OTP or OTP login. These are planned backend changes, not client workarounds.

## Workstream decomposition

The work is intentionally split into four independent plans. Each plan has its own tests and commit gates.

| Workstream | Plan | Produces |
| --- | --- | --- |
| Backend foundation | [backend plan](/Users/xiaox/WorkShop/ai-plush-companion-public/docs/superpowers/plans/2026-08-29-flutter-ai-companion-backend.md) | App auth, profile facade, durable conversations, shared memory, runtime contracts |
| Flutter shell | [client plan](/Users/xiaox/WorkShop/ai-plush-companion-public/docs/superpowers/plans/2026-08-29-flutter-ai-companion-client.md) | Project, auth UI, navigation, roles, devices, memory, account and chat shell |
| Conversation audio | [audio plan](/Users/xiaox/WorkShop/ai-plush-companion-public/docs/superpowers/plans/2026-08-29-flutter-ai-companion-audio.md) | WebSocket adapter, press-to-send voice, playback, background audio and calls |
| Device and acceptance | [device plan](/Users/xiaox/WorkShop/ai-plush-companion-public/docs/superpowers/plans/2026-08-29-flutter-ai-companion-device-acceptance.md) | SoftAP WebView flow, cloud binding, cross-entrypoint verification and release evidence |

## Dependency order

The backend authentication schema and profile/session contracts must land before the matching Flutter repositories. Durable conversation persistence must pass before the left history drawer is connected. Canonical memory mapping must pass before any cross-device memory claim. The audio WebSocket contract can be developed against a fake server, but full-screen calls cannot be accepted until the backend continuation route and binary TTS contract pass. Device provisioning can be built in parallel with audio after the Flutter shell exists, but cross-entrypoint acceptance waits for both.

## Task 1: Freeze the contract and prepare execution

**Files:**

- Reference: `docs/superpowers/specs/2026-08-29-flutter-ai-companion-design.md`
- Reference: `docs/public-conversation-api.yaml`
- Reference: `server/main/companion-web/lib/realtime.ts`
- Create: `docs/superpowers/plans/2026-08-29-flutter-ai-companion-contract-matrix.md`

- [ ] **Step 1: Record the route matrix.** Copy the exact request and response shapes from the design into a matrix grouped by auth, profiles, devices, conversations, memories, audio replay and WebSocket events. Mark existing routes as compatible and new routes as backend tasks; do not invent client-only endpoints.

- [ ] **Step 2: Record invariants.** Write these assertions into the matrix: profile version is fixed per App conversation; device profile changes apply at the next turn; role memory namespace is `companion:<userId>:<profileId>`; deleting a conversation does not delete memory; one playback queue exists per App; MQTT never appears in App code.

- [ ] **Step 3: Check the dirty worktree before implementation.**

```bash
git status --short
git diff --check
```

Expected: existing user changes are listed and no whitespace errors are introduced. Do not reset or clean unrelated files.

- [ ] **Step 4: Commit the contract matrix.**

```bash
git add docs/superpowers/plans/2026-08-29-flutter-ai-companion-contract-matrix.md
git commit -m "docs: freeze consumer app contract matrix"
```

## Task 2: Execute the backend workstream

**Files:**

- Use: `docs/superpowers/plans/2026-08-29-flutter-ai-companion-backend.md`
- Modify: `server/main/manager-api/**`
- Modify: `server/main/xiaozhi-server/core/public_conversation/**`

- [ ] **Step 1: Complete backend Tasks 1 and 2.** Add App contacts, challenges, refresh tokens, phone/email OTP, password login, registration and reset-password without changing `/user/*` legacy behavior.

- [ ] **Step 2: Run the authentication gate.**

```bash
cd server/main/manager-api
mvn -Dtest=xiaozhi.modules.appauth.*Test,xiaozhi.modules.security.config.ShiroConfigTest,xiaozhi.modules.security.oauth2.Oauth2FilterWebSessionTest test
```

Expected: all App auth and existing security tests pass.

- [ ] **Step 3: Complete backend Tasks 3 and 4.** Add consumer profile templates/catalogs, atomic save-publish-activate, soft deletion, durable conversation list/CRUD/continuation and historical audio regeneration.

- [ ] **Step 4: Run the profile and conversation gate.**

```bash
mvn -Dtest=xiaozhi.modules.companion.service.AppProfileFacadeTest,xiaozhi.modules.companion.controller.AppProfileControllerTest,xiaozhi.modules.conversation.CompanionConversationIndexServiceTest,xiaozhi.modules.conversation.PublicConversationControllerTest test
```

Expected: owner isolation, version activation, role delete protection, continuation and idempotent history append all pass.

- [ ] **Step 5: Complete backend Tasks 5 and 6.** Add profile memory routes, canonical namespace mapping, legacy migration, durable device turn ingestion and Python runtime continuation/archival.

- [ ] **Step 6: Run the shared-memory gate.**

```bash
mvn -Dtest=xiaozhi.modules.companion.memory.ProfileMemoryServiceTest,xiaozhi.modules.device.service.impl.CompanionMemoryCompatibilityTest test
cd ../xiaozhi-server
python -m pytest tests/test_public_conversation_memory_namespace.py tests/test_public_conversation_durable_history.py tests/test_public_conversation_http.py -q
```

Expected: App and hardware route shapes remain compatible, both map to one profile namespace, disabled memory performs no provider access, and duplicate turns are idempotent.

- [ ] **Step 7: Complete backend Task 7.** Publish `docs/app-auth-api.yaml` and migration evidence, then run the OpenAPI syntax check and `git diff --check`.

- [ ] **Step 8: Commit each backend slice separately.** Use the commit commands in the backend plan so failures can be reverted by capability boundary rather than by the whole App.

## Task 3: Execute the Flutter shell workstream

**Files:**

- Use: `docs/superpowers/plans/2026-08-29-flutter-ai-companion-client.md`
- Create: `app/**`

- [ ] **Step 1: Generate `app/` and lock dependencies.** Use the exact `flutter create` and `flutter pub add` commands in the client plan. Set `API_BASE_URL` and `RUNTIME_WS_ORIGIN` through `--dart-define`; never hard-code a production host.

- [ ] **Step 2: Complete client Tasks 2 and 3.** Implement strict API parsing, secure token/refresh behavior, phone/email login modes, registration, reset-password and first-run role onboarding.

- [ ] **Step 3: Run the auth shell gate.**

```bash
cd app
dart format --set-exit-if-changed lib test
flutter analyze
flutter test test/core test/features/auth test/features/onboarding
```

Expected: no analyzer errors and all auth/onboarding tests pass.

- [ ] **Step 4: Complete client Tasks 4 through 7.** Implement chat-first navigation, left conversation drawer, right role drawer, explicit profile editor, device/basic controls, role-scoped memory management, account settings and text chat integration.

- [ ] **Step 5: Run the shell gate.**

```bash
flutter test
flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
flutter build ios --no-codesign --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
```

Expected: all unit/widget tests pass and both debug builds compile. The placeholder host is used only to compile; no network success is claimed.

- [ ] **Step 6: Commit the shell workstream.** Use the per-task commit commands in the client plan.

## Task 4: Execute the conversation audio workstream

**Files:**

- Use: `docs/superpowers/plans/2026-08-29-flutter-ai-companion-audio.md`
- Modify: `app/lib/features/chat/**`
- Create: `app/lib/features/audio/**`
- Create: `app/lib/features/call/**`
- Modify: `server/main/xiaozhi-server/core/public_conversation/**`

- [ ] **Step 1: Complete audio Task 1.** Normalize WebSocket JSON/binary events, sequence handling, bearer subprotocol, text turns, audio commits, cancellation, heartbeat and stream stop.

- [ ] **Step 2: Run the transport gate.**

```bash
cd app
flutter test test/features/chat/realtime_event_decoder_test.dart test/features/chat/conversation_realtime_client_test.dart
cd ../../server/main/xiaozhi-server
python -m pytest tests/test_public_conversation_streaming_protocol.py tests/test_public_conversation_streaming_http.py -q
```

- [ ] **Step 3: Complete audio Tasks 2 and 3.** Implement local VAD, long-press/up-cancel capture, one-queue TTS playback, manual historical replay and background reply controls.

- [ ] **Step 4: Run single-turn audio tests.**

```bash
cd ../../../app
flutter test test/features/audio test/features/chat/message_composer_voice_test.dart
```

Expected: synthetic noise does not commit, speech commits once, upward cancellation sends no turn, new playback stops the old item and audio focus interruptions do not resend a message.

- [ ] **Step 5: Complete audio Tasks 4 and 5.** Implement the full-screen call state machine, continuous capture, VAD commit, interruption, mute, lock-screen lifecycle, Android foreground service, iOS audio session and server contract tests.

- [ ] **Step 6: Run call and binary-audio gates.**

```bash
flutter test test/features/call test/features/audio
cd ../server/main/xiaozhi-server
python -m pytest tests/test_public_conversation_call_contract.py tests/test_public_conversation_http.py -q
```

- [ ] **Step 7: Commit the audio workstream.** Use the per-task commit commands in the audio plan.

## Task 5: Execute device provisioning and cross-entrypoint acceptance

**Files:**

- Use: `docs/superpowers/plans/2026-08-29-flutter-ai-companion-device-acceptance.md`
- Create/modify: `app/lib/features/devices/provisioning/**`
- Create/modify: `app/integration_test/**`
- Create: `docs/consumer-app-acceptance-2026-08-29.md`

- [ ] **Step 1: Complete device Tasks 1 and 2.** Implement resumable WebView provisioning, exact `/done.html` detection, restricted local HTTP access and system Wi-Fi settings fallback.

- [ ] **Step 2: Run portal tests and builds.**

```bash
cd app
flutter test test/features/devices/provisioning
flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
flutter build ios --no-codesign --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
```

- [ ] **Step 3: Complete device Task 3.** After portal success, bind immediately with the six-digit activation code and selected profile; only then poll the owned-device list for cloud reappearance. Wire only alias, role, volume, brightness, reconfigure and unbind.

- [ ] **Step 4: Complete device Task 4.** Prove App/device durable history and shared profile memory with two devices, role isolation and deletion boundaries.

- [ ] **Step 5: Complete device Task 5.** Run Android and iOS account, chat/audio, provisioning recovery and release checks. Record sanitized evidence and explicitly verify no NVS or firmware image was erased or overwritten.

- [ ] **Step 6: Commit acceptance evidence.** Use the device plan's commit command after evidence files contain platform, build, profile-version and redacted event data.

## Task 6: Final requirement audit and release handoff

**Files:**

- Modify: `docs/project-requirements-audit.md`
- Modify: `docs/consumer-app-acceptance-2026-08-29.md`
- Modify: `README.md` only if the new `app/` setup needs a root-level pointer

- [ ] **Step 1: Map every design requirement to evidence.** Use the following proof points: auth tests for all four contact/mode paths; profile tests for copy/edit/version/delete; Flutter widget tests for both drawers and unsaved protection; WebSocket tests for text/audio/cancel/continuation; platform tests for background playback and calls; device evidence for SoftAP, activation and two-device memory; backend tests for owner isolation and legacy hardware compatibility.

- [ ] **Step 2: Run the complete static and test set.**

```bash
git diff --check
cd server/main/manager-api
mvn test
cd ../xiaozhi-server
python -m pytest -q
cd ../../../app
dart format --set-exit-if-changed lib test integration_test
flutter analyze
flutter test
```

Expected: each command passes in the documented environment. A missing real-device or platform result remains an acceptance gap, not a green substitute.

- [ ] **Step 3: Verify scope exclusions.** Search the App source and route matrix for subscription purchase, MQTT, provider secrets, advanced firmware/debug controls and raw audio persistence. These must not be present in the first release.

```bash
rg -n "mqtt|api[_-]?key|provider.*secret|firmware|wake.?word|debug.?log|subscription.*purchase|audio.*blob" app docs/superpowers/plans/2026-08-29-flutter-ai-companion-contract-matrix.md
```

Expected: only explanatory documentation or deliberately named test fixtures match; no App runtime path sends MQTT or secrets.

- [ ] **Step 4: Update the audit document with actual command output and real-device evidence.** Do not claim the App is complete from unit tests alone.

- [ ] **Step 5: Commit the final audit.**

```bash
git add docs/project-requirements-audit.md docs/consumer-app-acceptance-2026-08-29.md
git commit -m "docs: record consumer app release acceptance"
```

## Definition of done

The release is complete only when a new user can register or log in with phone or email, select or create a role, chat by text, send cancellable press-to-send audio, play replies in the background, enter a full-screen continuous call and resume it on the lock screen; manage persistent conversations and role memory; configure a device through the existing hotspot page, bind it with the six-digit code, assign a role and control volume/brightness; and observe App and hardware turns sharing the same role memory and history. Existing management, MQTT and hardware memory contracts must remain green throughout.

## Rollback boundaries

Authentication uses new `/app/auth` routes and tables, so disabling the App route does not disable existing `/user/*` login. Conversation persistence can be disabled while leaving short-lived Redis history in place. Canonical memory migration must retain legacy sources until verification passes and must be guarded by a feature flag. Flutter releases can be rolled back independently of firmware; no App test may require a full-image firmware flash or an NVS erase.
