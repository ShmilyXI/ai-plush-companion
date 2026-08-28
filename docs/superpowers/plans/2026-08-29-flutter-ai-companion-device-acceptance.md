# Flutter AI Companion Device and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the device provisioning flow, connect device controls to the Flutter shell, and prove the cross-entrypoint memory and conversation behavior on Android and iOS.

**Architecture:** The App owns a resumable provisioning state machine and embeds the existing device portal only while the phone is on the device hotspot. Cloud binding remains a separate authenticated operation using the existing six-digit activation route. Acceptance tests treat App, hardware, manager-api and Python as one chain while preserving MQTT and hardware request compatibility.

**Tech Stack:** Flutter integration tests, `webview_flutter`, `app_settings`, `permission_handler`, existing `/companion/devices` endpoints, ESP32 hotspot portal at `http://192.168.4.1`, manager-api and xiaozhi-server test suites.

---

## File map

Provisioning state and WebView navigation live under `app/lib/features/devices/provisioning/`. Cloud device mutations stay in the device repository from the client plan. Acceptance scripts and evidence live under `app/integration_test/` and `docs/consumer-app-acceptance-*.md`; no firmware binary or NVS image is modified by this plan.

## Task 1: Implement the resumable provisioning state machine

**Files:**

- Add dependency: `app/pubspec.yaml` (`app_settings`)
- Create: `app/lib/features/devices/provisioning/provisioning_models.dart`
- Create: `app/lib/features/devices/provisioning/provisioning_controller.dart`
- Create: `app/lib/features/devices/provisioning/provisioning_repository.dart`
- Create: `app/lib/features/devices/provisioning/provisioning_page.dart`
- Create: `app/test/features/devices/provisioning/provisioning_controller_test.dart`
- Create: `app/test/features/devices/provisioning/provisioning_page_test.dart`

- [ ] **Step 1: Write state-machine tests.** Cover `idle -> hotspotInstructions -> portal -> waitingForDevice -> activationCode -> binding -> success`, cancellation from every nonterminal state, WebView load failure, `/submit` failure, `/done.html` navigation, device timeout, invalid activation code and retry after returning from system Wi-Fi settings.

- [ ] **Step 2: Implement typed provisioning states.** Include `devicePortalUri`, `startedAt`, `lastError`, `selectedProfileId`, `activationCodeDraft` and a monotonically increasing attempt ID. A stale WebView callback must not advance a newer attempt.

- [ ] **Step 3: Implement system-settings fallback.** Use `app_settings` to open Wi-Fi settings when the OS does not allow programmatic hotspot switching. Register `WidgetsBindingObserver` and call `resume()` on `AppLifecycleState.resumed` to restore the current state rather than restarting the wizard.

- [ ] **Step 4: Build the provisioning page.** Show one action at a time: scan and confirm the device hotspot, open portal, wait for success, enter six digits, select a role, bind. The hotspot label comes from the user's selected network or a scan result; do not assume a fixed `Xiaozhi-XXXXXX` prefix. Keep the WebView isolated from authenticated headers and disable arbitrary external navigation.

- [ ] **Step 5: Run provisioning unit/widget tests.**

```bash
cd app
flutter test test/features/devices/provisioning
```

- [ ] **Step 6: Commit the state machine.**

```bash
git add app/pubspec.yaml app/lib/features/devices/provisioning app/test/features/devices/provisioning
git commit -m "feat: add resumable device provisioning flow"
```

## Task 2: Embed the existing device portal safely

**Files:**

- Modify: `app/lib/features/devices/provisioning/provisioning_page.dart`
- Create: `app/lib/features/devices/provisioning/device_portal_delegate.dart`
- Modify: `app/android/app/src/main/AndroidManifest.xml`
- Create: `app/android/app/src/main/res/xml/network_security_config.xml`
- Modify: `app/android/app/src/main/AndroidManifest.xml` to reference the network security config
- Modify: `app/ios/Runner/Info.plist`
- Create: `app/test/features/devices/provisioning/device_portal_delegate_test.dart`

- [ ] **Step 1: Write navigation-delegate tests.** Accept only `http://192.168.4.1/` and its portal paths; emit `portalSucceeded` for exact host/path `/done.html`; reject external hosts, HTTPS redirects to unrelated hosts and malformed URLs. Verify that the success callback waits for the completion page's `/exit` request before closing the WebView.

- [ ] **Step 2: Implement the delegate.** Intercept rather than inject JavaScript. The existing page itself submits JSON to `/submit` and its completion page sends `/exit`; after observing `/done.html`, wait for the page request or explicitly issue a local `POST http://192.168.4.1/exit` before closing. The App never sends Wi-Fi credentials through the cloud API.

```dart
NavigationDecision handleNavigation(NavigationRequest request) {
  final uri = Uri.tryParse(request.url);
  if (uri == null || uri.scheme != 'http' || uri.host != '192.168.4.1') {
    return NavigationDecision.prevent;
  }
  if (uri.path == '/done.html') onPortalSucceeded();
  return NavigationDecision.navigate;
}
```

- [ ] **Step 3: Configure restricted cleartext access.** Permit HTTP only for the local device address on Android and add the smallest iOS ATS exception needed for `192.168.4.1`. Keep production cloud traffic HTTPS-only.

- [ ] **Step 4: Run delegate tests and platform builds.**

```bash
cd app
flutter test test/features/devices/provisioning/device_portal_delegate_test.dart
flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
flutter build ios --no-codesign --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
```

- [ ] **Step 5: Commit the portal integration.**

```bash
git add app/lib/features/devices/provisioning app/android app/ios app/test/features/devices/provisioning/device_portal_delegate_test.dart
git commit -m "feat: embed device wifi portal"
```

## Task 3: Complete cloud binding and basic device controls

**Files:**

- Modify: `app/lib/features/devices/data/device_repository.dart`
- Modify: `app/lib/features/devices/application/device_controller.dart`
- Modify: `app/lib/features/devices/provisioning/provisioning_controller.dart`
- Modify: `app/lib/features/devices/presentation/device_list_page.dart`
- Modify: `app/lib/features/devices/presentation/device_detail_page.dart`
- Create: `app/test/features/devices/provisioning/cloud_binding_test.dart`
- Create: `app/test/features/devices/device_detail_page_test.dart`

- [ ] **Step 1: Write binding tests.** Assert the controller refuses non-six-digit input, sends `{activationCode, profileId}` to `POST /companion/devices/bind`, refreshes the list after success, and leaves the wizard in `activationCode` on 401, 409 or timeout.

- [ ] **Step 2: Implement the post-portal bind sequence.** Do not poll the owned-device list before binding because an unbound device cannot appear there. Show the six-digit activation-code form immediately after portal success, submit `POST /companion/devices/bind` with the selected profile, then poll `GET /companion/devices` with exponential delays capped at 30 seconds for a bounded five-minute window. Match the newly owned device by the bind response or a user-confirmed MAC; never assume the first returned device is the one being configured.

- [ ] **Step 3: Bind and assign the selected role.** Submit the six-digit activation code with the selected profile ID. If the bind succeeds without a profile, call the profile switch route explicitly and display the server's active profile in the result.

- [ ] **Step 4: Wire basic controls.** Use existing `PUT /companion/devices/{id}` for alias, `PUT /{id}/profile` for role, `POST /{id}/commands` for volume/brightness and `DELETE /{id}` for unbind. Do not add wake-word, firmware, debug-log or Skill controls to the App.

- [ ] **Step 5: Run device tests.**

```bash
cd app
flutter test test/features/devices
```

- [ ] **Step 6: Commit cloud binding and controls.**

```bash
git add app/lib/features/devices app/test/features/devices
git commit -m "feat: bind and control companion devices"
```

## Task 4: Prove App/device conversation and memory sharing

**Files:**

- Create: `app/integration_test/cross_entrypoint_memory_test.dart`
- Create: `app/integration_test/device_conversation_history_test.dart`
- Create: `docs/consumer-app-acceptance-2026-08-29.md`
- Modify: `docs/project-requirements-audit.md`
- Use: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/memory/ProfileMemoryServiceTest.java`
- Use: `server/main/xiaozhi-server/tests/test_public_conversation_memory_namespace.py`

- [ ] **Step 1: Create a deterministic staging fixture.** Provision two test devices owned by one test user, bind both to profile `p1`, and seed a non-sensitive memory through the service test fixture. Record only opaque IDs and counts in the evidence file.

- [ ] **Step 2: Run the App turn.** Send one text or audio turn from the App, wait for durable history append, and assert the conversation list contains a single turn with `source=app`.

- [ ] **Step 3: Run the hardware turn.** Speak one turn on device A, wait for device history ingestion, and assert the same user/profile memory namespace contains the new item and the App conversation list contains `source=device`.

- [ ] **Step 4: Verify the second device.** Start a new turn on device B with profile `p1` and assert it can recall the memory from device A without a migration endpoint or device-specific namespace suffix.

- [ ] **Step 5: Verify role isolation.** Switch device A to profile `p2`, wait one turn boundary, and assert p2 cannot read p1's memory while p1's memory remains visible from device B and the App.

- [ ] **Step 6: Verify deletion boundaries.** Delete an App conversation and assert the profile memory count is unchanged; clear profile memory and assert the next App and device reads return an empty item set while memory remains enabled. Then disable the role and assert runtime reads and automatic writes stop while the management list still exposes any remaining items for user deletion.

- [ ] **Step 7: Run integration evidence commands.**

```bash
cd server/main/manager-api
mvn -Dtest=xiaozhi.modules.companion.memory.ProfileMemoryServiceTest test
cd ../xiaozhi-server
python -m pytest tests/test_public_conversation_memory_namespace.py tests/test_public_conversation_durable_history.py -q
cd ../../../app
flutter test integration_test/cross_entrypoint_memory_test.dart integration_test/device_conversation_history_test.dart
```

- [ ] **Step 8: Commit the acceptance evidence.**

```bash
git add app/integration_test docs/consumer-app-acceptance-2026-08-29.md docs/project-requirements-audit.md
git commit -m "test: verify cross-entrypoint companion memory"
```

## Task 5: Run Android and iOS device acceptance

**Files:**

- Create: `app/integration_test/auth_and_chat_smoke_test.dart`
- Create: `app/integration_test/provisioning_recovery_test.dart`
- Create: `app/integration_test/background_audio_acceptance_test.dart`
- Modify: `docs/consumer-app-acceptance-2026-08-29.md`

- [ ] **Step 1: Run account smoke tests on Android and iOS.** Exercise phone/password login, phone/code login, email/password login, email/code login, registration with required password, reset-password code verification, token refresh and logout.

- [ ] **Step 2: Run chat/audio tests on both platforms.** Exercise text, press-to-send audio, upward cancellation, manual reply playback, automatic playback, background playback, full-screen call, mute, interruption, hang-up, lock-screen continuation and notification controls.

- [ ] **Step 3: Run provisioning recovery tests.** Exercise portal success, `/submit` failure, hotspot disconnect, returning from system Wi-Fi settings, device timeout, wrong activation code, successful binding and role assignment. Do not flash firmware or erase NVS during these tests.

- [ ] **Step 4: Capture sanitized evidence.** Record app version, platform, profile version, conversation ID hash, device ID hash, event types and error codes. Exclude passwords, OTPs, Wi-Fi credentials, audio, prompt text and memory content.

- [ ] **Step 5: Run release checks.**

```bash
cd app
dart format --set-exit-if-changed lib test integration_test
flutter analyze
flutter test
flutter build apk --release --dart-define=API_BASE_URL=https://api.example.invalid/xiaozhi
flutter build ios --release --no-codesign --dart-define=API_BASE_URL=https://api.example.invalid/xiaozhi
```

Expected: analyzer and tests pass; release builds contain only the declared microphone, notification, network and background-audio capabilities.

- [ ] **Step 6: Commit final acceptance evidence.**

```bash
git add app/integration_test docs/consumer-app-acceptance-2026-08-29.md
git commit -m "test: complete Android and iOS companion acceptance"
```

## Device gate

Do not mark the consumer App complete until the portal callback, cloud binding, role assignment, device controls, cross-device memory, shared history, background audio and lock-screen call behavior have evidence on both platforms. Existing firmware and MQTT tests must remain green, and no full-image flash may be used as part of App validation.
