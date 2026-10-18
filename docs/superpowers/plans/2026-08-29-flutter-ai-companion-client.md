# Flutter AI Companion Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the native Android/iOS Flutter shell for authentication, onboarding, navigation, roles, devices, memory, account settings, and the consumer chat entry points.

**Architecture:** The app is a thin native client over the backend contract in `2026-08-29-flutter-ai-companion-backend.md`. Riverpod owns feature state and dependency injection; pages never call Dio or WebSocket directly. Sensitive auth tokens use `flutter_secure_storage`, ordinary preferences use `shared_preferences`, and a separate audio plan owns recording, playback, and real-time call state.

**Tech Stack:** Flutter 3.35/Dart 3.9, `flutter_riverpod`, `go_router`, `dio`, `web_socket_channel`, `record`, `just_audio`, `audio_service`, `audio_session`, `webview_flutter`, `permission_handler`, `url_launcher`, `flutter_secure_storage`, `shared_preferences`, `path_provider`, `uuid`, `intl`, `json_annotation`, `json_serializable`, `mocktail`.

---

## File map

Create the Flutter project at `app/`. Keep feature code under `app/lib/features/<feature>/` with `data`, `domain`, and `presentation` boundaries. `app/lib/core/` contains cross-feature infrastructure only. Tests mirror the production path under `app/test/`; platform integration tests live under `app/integration_test/`.

## Task 1: Scaffold the Flutter project and platform baseline

**Files:**

- Create: `app/` with `flutter create --org com.xiaozhi --project-name ai_plush_companion`
- Modify: `app/pubspec.yaml`
- Modify: `app/analysis_options.yaml`
- Create: `app/.env.example`
- Create: `app/lib/main.dart`
- Create: `app/lib/app.dart`
- Create: `app/lib/core/config/app_config.dart`
- Create: `app/test/smoke_test.dart`
- Modify: `app/android/app/src/main/AndroidManifest.xml`
- Modify: `app/ios/Runner/Info.plist`
- Modify: `app/ios/Runner/Runner.entitlements`

- [ ] **Step 1: Verify the target directory is unused.**

```bash
test ! -e app
```

Expected: exit code `0`.

- [ ] **Step 2: Generate the project and add dependencies.**

```bash
flutter create --org com.xiaozhi --project-name ai_plush_companion app
cd app
flutter pub add flutter_riverpod go_router dio web_socket_channel record just_audio audio_service audio_session webview_flutter permission_handler url_launcher flutter_secure_storage shared_preferences path_provider uuid intl json_annotation
flutter pub add --dev build_runner json_serializable mocktail
```

- [ ] **Step 3: Add environment configuration.** Define `AppConfig.fromEnvironment()` with `API_BASE_URL` and `RUNTIME_WS_ORIGIN` compile-time values. Reject an empty HTTPS API base URL in release mode; allow `http://10.0.2.2` and localhost only in debug builds.

```dart
class AppConfig {
  const AppConfig({required this.apiBaseUrl, this.runtimeWsOrigin});
  final Uri apiBaseUrl;
  final Uri? runtimeWsOrigin;

  factory AppConfig.fromEnvironment() {
    final raw = const String.fromEnvironment('API_BASE_URL');
    if (raw.isEmpty) throw StateError('API_BASE_URL is required');
    return AppConfig(
      apiBaseUrl: Uri.parse(raw),
      runtimeWsOrigin: Uri.tryParse(const String.fromEnvironment('RUNTIME_WS_ORIGIN')),
    );
  }
}
```

- [ ] **Step 4: Configure platform permissions without enabling unused capabilities.** Add Internet, microphone, foreground service and notification declarations on Android; add microphone usage, background audio mode and notification categories on iOS. Do not add camera, contacts, Bluetooth or location permissions because the first provisioning flow uses system Wi-Fi settings and the existing HTTP page.

- [ ] **Step 5: Wire a minimal app and smoke test.** `main.dart` initializes Flutter bindings and `ProviderScope`; `app.dart` renders a `MaterialApp.router` with a temporary `/health` route. The smoke test asserts the route renders.

```bash
cd app
dart format --set-exit-if-changed lib test
flutter analyze
flutter test test/smoke_test.dart
```

Expected: analyzer clean and one passing test.

- [ ] **Step 6: Commit the scaffold.**

```bash
git add app
git commit -m "feat: scaffold Flutter companion app"
```

## Task 2: Build strict REST, token storage, and feature providers

**Files:**

- Create: `app/lib/core/network/api_client.dart`
- Create: `app/lib/core/network/api_result.dart`
- Create: `app/lib/core/network/auth_interceptor.dart`
- Create: `app/lib/core/network/api_exception.dart`
- Create: `app/lib/core/storage/secure_store.dart`
- Create: `app/lib/core/storage/preferences_store.dart`
- Create: `app/lib/core/providers/core_providers.dart`
- Create: `app/test/core/network/api_client_test.dart`
- Create: `app/test/core/network/auth_interceptor_test.dart`
- Create: `app/test/core/storage/secure_store_test.dart`

- [ ] **Step 1: Write red parser tests.** Assert that `{code:0,msg:"",data:...}` unwraps, nonzero codes become `ApiException`, malformed payloads are rejected, and 401 is distinguishable from 409 and `memory_disabled`.

- [ ] **Step 2: Implement the typed API result parser.** Every repository call must pass through one parser; no page may cast `dynamic` response maps.

```dart
T unwrap<T>(Map<String, dynamic> json, T Function(Object? data) decode) {
  final code = json['code'];
  if (code is! int || json['msg'] is! String || !json.containsKey('data')) {
    throw const ApiException.protocol('invalid api envelope');
  }
  if (code != 0) throw ApiException.business(code, json['msg'] as String, json['data']);
  return decode(json['data']);
}
```

- [ ] **Step 3: Implement secure token storage.** Store access token, refresh token, expiry timestamps and current user ID only through `FlutterSecureStorage`; expose `read`, `writeSession`, and `clear`. Never store a runtime token or password.

- [ ] **Step 4: Implement one-refresh request interception.** On a 401, queue concurrent requests behind one refresh call to `/app/auth/refresh`; retry each request once with the new access token. On refresh failure clear secure storage and notify the router through an `authExpired` stream.

- [ ] **Step 5: Add provider wiring.** Create `apiClientProvider`, `secureStoreProvider`, `preferencesStoreProvider`, `authRepositoryProvider`, and feature repository providers. Use `ProviderScope` overrides in tests to inject a fake Dio adapter.

- [ ] **Step 6: Run infrastructure tests.**

```bash
cd app
flutter test test/core/network test/core/storage
```

Expected: all parser, refresh deduplication and storage tests pass.

- [ ] **Step 7: Commit the infrastructure slice.**

```bash
git add app/lib/core app/test/core
git commit -m "feat: add Flutter API and secure storage layers"
```

## Task 3: Implement authentication and first-run onboarding

**Files:**

- Create: `app/lib/features/auth/domain/auth_models.dart`
- Create: `app/lib/features/auth/data/auth_repository.dart`
- Create: `app/lib/features/auth/application/auth_controller.dart`
- Create: `app/lib/features/auth/presentation/login_page.dart`
- Create: `app/lib/features/auth/presentation/register_page.dart`
- Create: `app/lib/features/auth/presentation/reset_password_page.dart`
- Create: `app/lib/features/auth/presentation/contact_code_field.dart`
- Create: `app/lib/features/auth/presentation/contact_mode_toggle.dart`
- Create: `app/lib/features/onboarding/presentation/onboarding_page.dart`
- Create: `app/lib/features/onboarding/presentation/role_seed_page.dart`
- Create: `app/test/features/auth/auth_controller_test.dart`
- Create: `app/test/features/auth/login_page_test.dart`
- Create: `app/test/features/auth/register_page_test.dart`
- Create: `app/test/features/auth/reset_password_page_test.dart`
- Create: `app/test/features/onboarding/onboarding_page_test.dart`

- [ ] **Step 1: Define authentication models and repository methods.** Use `ContactChannel.phone|email`, `CodePurpose.login|register|reset|bind`, and explicit methods `sendCode`, `passwordLogin`, `codeLogin`, `register`, `resetPassword`, `refresh`, `logout`, and `bindContact`. Map endpoint payloads exactly to the backend plan.

- [ ] **Step 2: Write red controller tests.** Cover password login, phone/email code login, registration requiring both a verified challenge and password, reset-password code verification, resend countdown, and invalid-contact messages. Assert no password or code is persisted.

- [ ] **Step 3: Implement the auth controller state machine.** States are `signedOut`, `sendingCode`, `codeSent`, `authenticating`, `authenticated`, and `failure`; ignore stale code-send responses by request ID. Persist only the returned token pair.

- [ ] **Step 4: Build the login page.** Provide a segmented control for password/code mode and phone/email channel. Password mode accepts contact plus password; code mode accepts contact, code and resend. The page has no graph-captcha widget.

- [ ] **Step 5: Build registration and reset pages.** Registration requires contact code, password and password confirmation. Reset requires contact code, new password and confirmation. The reset flow cannot submit until the challenge is verified by the server.

- [ ] **Step 6: Build first-run onboarding.** When `/app/profiles` returns empty, show preset/custom role seed selection with a skip-device action. Existing users go to the last conversation; no device is required to enter chat.

- [ ] **Step 7: Run widget and controller tests.**

```bash
cd app
flutter test test/features/auth test/features/onboarding
```

Expected: all auth paths and first-run branching pass.

- [ ] **Step 8: Commit the auth slice.**

```bash
git add app/lib/features/auth app/lib/features/onboarding app/test/features/auth app/test/features/onboarding
git commit -m "feat: add Flutter consumer authentication"
```

## Task 4: Add routing, theme, and the chat-first shell

**Files:**

- Create: `app/lib/core/routing/app_router.dart`
- Create: `app/lib/core/theme/app_theme.dart`
- Create: `app/lib/core/l10n/app_zh.arb`
- Create: `app/lib/core/l10n/app_localizations.dart`
- Create: `app/lib/features/shell/presentation/app_shell.dart`
- Create: `app/lib/features/shell/presentation/bottom_navigation.dart`
- Create: `app/lib/features/chat/presentation/chat_page.dart`
- Create: `app/test/core/routing/app_router_test.dart`
- Create: `app/test/features/shell/app_shell_test.dart`

- [ ] **Step 1: Write route-guard tests.** Assert signed-out users go to `/login`, authenticated users with no profile go to `/onboarding`, and authenticated users with profiles enter `/chat`.

- [ ] **Step 2: Implement `go_router` branches.** Use an auth-refresh listenable rather than redirecting from page builds. Keep route names stable: `login`, `register`, `resetPassword`, `onboarding`, `chat`, `devices`, `profiles`, `account`, `call`.

- [ ] **Step 3: Implement the chat-first shell.** Bottom navigation contains Chat, Devices, Roles and Me; Chat is the default route. Preserve each tab's scroll and provider state while switching tabs.

- [ ] **Step 4: Add Chinese localization scaffolding.** Put all visible strings in `app_zh.arb`; configure generated localization even though only Chinese ships in this release. Do not hard-code server error text into widgets.

- [ ] **Step 5: Add responsive theme and accessibility labels.** Use stable icon button dimensions, semantic labels, large-text-safe layouts, and explicit loading/empty/error states. The shell must work at 320 logical pixels wide.

- [ ] **Step 6: Run shell tests.**

```bash
cd app
dart format --set-exit-if-changed lib test
flutter test test/core/routing test/features/shell
```

- [ ] **Step 7: Commit the shell.**

```bash
git add app/lib/core/routing app/lib/core/theme app/lib/core/l10n app/lib/features/shell app/lib/features/chat app/test/core/routing app/test/features/shell
git commit -m "feat: add chat-first Flutter app shell"
```

## Task 5: Implement role catalog, editor, and role selection drawer

**Files:**

- Create: `app/lib/features/profiles/domain/profile_models.dart`
- Create: `app/lib/features/profiles/data/profile_repository.dart`
- Create: `app/lib/features/profiles/application/profile_controller.dart`
- Create: `app/lib/features/profiles/presentation/profile_list_page.dart`
- Create: `app/lib/features/profiles/presentation/profile_editor_page.dart`
- Create: `app/lib/features/profiles/presentation/profile_selector_drawer.dart`
- Create: `app/lib/features/profiles/presentation/profile_form_sections.dart`
- Create: `app/test/features/profiles/profile_controller_test.dart`
- Create: `app/test/features/profiles/profile_editor_page_test.dart`
- Create: `app/test/features/profiles/profile_selector_drawer_test.dart`

- [ ] **Step 1: Define strict profile models.** Include `id`, `name`, `avatarUrl`, `personality`, `systemPrompt`, `relationMode`, `models`, `voice`, `capabilities`, `memoryEnabled`, `activeVersionNo`, `boundDevices`, `sourceTemplateId`, and `deleted` state. Reject missing IDs and unknown capability fields.

- [ ] **Step 2: Implement repository methods.** Add `listProfiles`, `getProfile`, `listTemplates`, `listModelOptions`, `listCapabilityOptions`, `createFromTemplate`, `createCustom`, `saveAndActivate`, `setMemoryEnabled`, `deleteProfile`, and `generateAvatarUpload`. Do not expose provider credentials in model classes.

- [ ] **Step 3: Write editor red tests.** Assert every editable field is represented, capability controls are boolean-only, save is disabled while invalid, and a failed save leaves the last server profile unchanged.

- [ ] **Step 4: Build the explicit-save editor.** Group identity, model/voice, personality/prompt, capabilities, memory, and bound devices. Keep a local draft separate from the active profile. On back with dirty state, require save or discard.

- [ ] **Step 5: Build the role selector drawer.** The chat page opens it from the top-right control. Cards show avatar/name/summary, current selection, create-role card, and the persistent memory switch. Switching role calls `ConversationController.startNewForProfile` and never mutates the old conversation.

- [ ] **Step 6: Implement profile deletion confirmation.** Disable delete when bound-device count is nonzero; otherwise call soft delete and remove the role from selectable state while keeping historical labels.

- [ ] **Step 7: Run profile tests.**

```bash
cd app
flutter test test/features/profiles
```

- [ ] **Step 8: Commit the profile slice.**

```bash
git add app/lib/features/profiles app/test/features/profiles
git commit -m "feat: add consumer profile management"
```

## Task 6: Implement devices, memory management, and account settings

**Files:**

- Create: `app/lib/features/devices/domain/device_models.dart`
- Create: `app/lib/features/devices/data/device_repository.dart`
- Create: `app/lib/features/devices/application/device_controller.dart`
- Create: `app/lib/features/devices/presentation/device_list_page.dart`
- Create: `app/lib/features/devices/presentation/device_detail_page.dart`
- Create: `app/lib/features/memory/domain/memory_models.dart`
- Create: `app/lib/features/memory/data/memory_repository.dart`
- Create: `app/lib/features/memory/application/memory_controller.dart`
- Create: `app/lib/features/memory/presentation/profile_memory_page.dart`
- Create: `app/lib/features/account/domain/account_models.dart`
- Create: `app/lib/features/account/data/account_repository.dart`
- Create: `app/lib/features/account/application/account_controller.dart`
- Create: `app/lib/features/account/presentation/account_page.dart`
- Create: `app/lib/features/account/presentation/contact_binding_page.dart`
- Create: `app/test/features/devices/device_controller_test.dart`
- Create: `app/test/features/memory/memory_controller_test.dart`
- Create: `app/test/features/account/account_page_test.dart`

- [ ] **Step 1: Implement device repository and tests.** Reuse `/companion/devices` list/detail/bind/update/profile/commands/delete routes. Model online status, alias, active profile, display/camera flags and last connection without accepting MQTT credentials.

- [ ] **Step 2: Build device list/detail pages.** Show online state, alias, current role, volume and brightness controls, reconfigure action and unbind action. Device command errors leave the slider draft unchanged and show a retryable message.

- [ ] **Step 3: Implement profile-scoped memory repository.** Use `/companion/profiles/{profileId}/memories`; map `enabled=false` to a runtime-disabled banner while still rendering returned items for user management. Allow edit, single delete and clear regardless of the runtime toggle. Do not render an add-memory button.

- [ ] **Step 4: Build memory page.** List, edit, delete and clear with per-item loading, optimistic updates only after server success, and a clear-all confirmation. Show that memory is shared with devices without exposing device credentials.

- [ ] **Step 5: Build account page.** Show current user, verified phone/email, bind the missing contact, change password, logout, and the global auto-play preference. Keep subscription purchase and plan controls out of the page.

- [ ] **Step 6: Run feature tests.**

```bash
cd app
flutter test test/features/devices test/features/memory test/features/account
```

- [ ] **Step 7: Commit the account/device slice.**

```bash
git add app/lib/features/devices app/lib/features/memory app/lib/features/account app/test/features/devices app/test/features/memory app/test/features/account
git commit -m "feat: add device memory and account pages"
```

## Task 7: Integrate the chat page with the conversation controller

**Files:**

- Create: `app/lib/features/chat/domain/chat_models.dart`
- Create: `app/lib/features/chat/data/conversation_repository.dart`
- Create: `app/lib/features/chat/application/conversation_controller.dart`
- Create: `app/lib/features/chat/presentation/conversation_drawer.dart`
- Create: `app/lib/features/chat/presentation/message_list.dart`
- Create: `app/lib/features/chat/presentation/message_composer.dart`
- Create: `app/lib/features/chat/presentation/reply_audio_button.dart`
- Create: `app/test/features/chat/conversation_controller_test.dart`
- Create: `app/test/features/chat/conversation_drawer_test.dart`
- Create: `app/test/features/chat/message_composer_test.dart`

- [ ] **Step 1: Define conversation models and repository methods.** Include source (`app` or `device`), title, role snapshot, profile version, last activity, turns, deletion state, and runtime session metadata. Add list/create/continue/rename/delete/history/audio-regeneration calls.

- [ ] **Step 2: Write red state tests.** Cover selecting a history item, creating a new conversation, renaming, deleting without deleting memory, merging a device-created item, and refusing to replace an active turn on reconnect.

- [ ] **Step 3: Implement the conversation controller.** Keep selected conversation, loaded history, unsent draft, active turn and connection state separate. When a role changes, close the old runtime and create a new conversation.

- [ ] **Step 4: Build the left conversation drawer.** Include new conversation, paged App/device history, rename, delete confirmation, loading and empty states. Selecting a device conversation only loads it after the device session is no longer active.

- [ ] **Step 5: Build message rendering and composer placeholders.** Render streamed text, user/assistant roles, source badges and a manual reply-audio button. Wire text send to the audio plan's WebSocket adapter without duplicating transport code.

- [ ] **Step 6: Run chat tests and static checks.**

```bash
cd app
flutter test test/features/chat
flutter analyze
```

- [ ] **Step 7: Commit the chat shell integration.**

```bash
git add app/lib/features/chat app/test/features/chat
git commit -m "feat: add persistent conversation UI"
```

## Client gate before audio and provisioning

At this gate, `flutter analyze`, all unit/widget tests, and a debug build for both platforms must pass. Run:

```bash
cd app
flutter test
flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
flutter build ios --no-codesign --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
```

The builds may not connect to a server, but they must compile the permission declarations, routing branches, secure storage, and all non-audio pages.
