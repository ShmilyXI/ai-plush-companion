# Virtual Playground Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a virtual-device playground where users can compose an existing companion profile and model stack, run text and voice conversations, exercise ASR/TTS/vision/activity/tool paths, and inspect temporary memory results while browser history stays in `localStorage`.

**Architecture:** `manager-api` owns an authenticated, immutable playground snapshot and coordinates a short-lived Python runtime context. `xiaozhi-server` executes the same connection-level pipeline against a synthetic device capability set and emits structured events and audio results. The React console owns the fixed three-column UI and versioned browser-local history, never storing secrets or audio blobs.

**Tech Stack:** Spring Boot Java controllers/services/DTOs, Python `aiohttp` and existing `ConnectionHandler` providers, React 19 + TypeScript, Ant Design 5, Axios, SSE, Vitest, pytest, Playwright.

---

### Task 1: Define and implement the manager-api playground session contract

**Files:**
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/controller/CompanionPlaygroundController.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/dto/PlaygroundSessionCreateDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/dto/PlaygroundInputDTO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/vo/PlaygroundSessionVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/vo/PlaygroundEventVO.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/service/CompanionPlaygroundService.java`
- Create: `server/main/manager-api/src/main/java/xiaozhi/modules/companion/playground/service/impl/CompanionPlaygroundServiceImpl.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/playground/CompanionPlaygroundControllerTest.java`
- Test: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/playground/CompanionPlaygroundServiceTest.java`

- [ ] **Step 1: Write contract tests first.** Cover an owned profile with enabled model and skill references, rejection of another user's profile, rejection of secret-bearing request fields, immutable snapshot content after the caller changes draft data, and session expiry. Assert the endpoint shape uses `sessionId`, `snapshotVersion`, `effectiveConfig`, `eventStreamPath`, and `expiresAt`.

- [ ] **Step 2: Run the focused Java tests and verify they fail.** Run `./gradlew :manager-api:test --tests '*CompanionPlayground*'` from `server/main/manager-api`. Expected result is failing tests because the controller, service and DTOs do not exist.

- [ ] **Step 3: Add typed request and response objects.** `PlaygroundSessionCreateDTO` must contain `profileId`, optional published `profileVersionId`, model references by `ModelType`, optional `ttsVoiceId`, skill bindings, prompt overrides, and a `virtualDevice` object containing positive `width`, `height`, `depth`, orientation, and feature flags. `PlaygroundInputDTO` must model exactly one of text, audio reference, vision image reference, or activity payload and reject empty or mixed inputs with Bean Validation.

  Use one explicit discriminated shape so invalid mixed inputs fail before the runtime call:

  ```java
  public enum PlaygroundInputKind { TEXT, AUDIO, VISION, ACTIVITY }

  public record PlaygroundInputDTO(
      @NotBlank String sessionId,
      @NotNull PlaygroundInputKind kind,
      String text,
      String audioRef,
      String imageRef,
      Map<String, Object> activity) {}
  ```

- [ ] **Step 4: Implement the service with an expiring in-memory store.** Resolve profile, model, voice, and published skill data through existing companion services. Copy only non-secret effective values into an immutable snapshot record. Generate a random session id, set a bounded expiry, and store owner id plus snapshot. Add methods `create`, `get`, `acceptInput`, `events`, `stream`, and `close`; every method must check owner id before reading or mutating state.

- [ ] **Step 5: Add the authenticated controller endpoints.** Use `@RequestMapping("/companion/playground/sessions")` and `@RequiresPermissions("sys:role:normal")`. Expose `POST /`, `GET /{id}`, `POST /{id}/inputs`, `GET /{id}/events` as `text/event-stream`, and `DELETE /{id}`. Return the existing `Result<T>` envelope for JSON endpoints and complete the SSE emitter on expiry or close.

- [ ] **Step 6: Run the focused tests and commit.** Run `./gradlew :manager-api:test --tests '*CompanionPlayground*'`. Expected result is PASS. Commit with `feat(manager-api): add virtual playground session contract`.

### Task 2: Add the Python virtual runtime bridge and event protocol

**Files:**
- Create: `server/main/xiaozhi-server/core/playground/protocol.py`
- Create: `server/main/xiaozhi-server/core/playground/session.py`
- Create: `server/main/xiaozhi-server/core/playground/service.py`
- Modify: `server/main/xiaozhi-server/core/http_server.py`
- Modify: `server/main/xiaozhi-server/config/config.yaml`
- Test: `server/main/xiaozhi-server/tests/test_playground_protocol.py`
- Test: `server/main/xiaozhi-server/tests/test_playground_session.py`

- [ ] **Step 1: Write protocol tests first.** Verify an input envelope accepts text, audio, vision, and activity variants; rejects multiple variants; preserves a `session_id`, `sequence`, and `snapshot_version`; and serializes events with `capability`, `stage`, `status`, `started_at`, `finished_at`, `duration_ms`, `input_summary`, `output_summary`, and `error`.

- [ ] **Step 2: Run `pytest tests/test_playground_protocol.py tests/test_playground_session.py -q` and verify failure.** Expected result is import or assertion failure because the playground package is absent.

- [ ] **Step 3: Implement `protocol.py` with dataclasses and strict parsing.** Define `PlaygroundInput`, `PlaygroundEvent`, `VirtualDevice`, and `PlaygroundSnapshot`. Keep summaries redacted and bounded; never serialize credentials, authorization headers, raw audio, or image bytes into events.

  The event serializer should expose a stable wire shape:

  ```python
  @dataclass(frozen=True)
  class PlaygroundEvent:
      session_id: str
      sequence: int
      capability: str
      stage: str
      status: Literal["started", "completed", "failed"]
      started_at: int
      finished_at: int | None = None
      duration_ms: int | None = None
      input_summary: str = ""
      output_summary: str = ""
      error: str | None = None
  ```

- [ ] **Step 4: Implement `session.py` as a connection-scoped adapter.** Construct a synthetic device id prefixed with `playground-`, inject the snapshot's prompt/model/voice/skill configuration into a copy of the normal connection config, expose the snapshot's camera, microphone, screen and orientation capabilities, and route text/audio/vision/activity inputs through the existing `ConnectionHandler` and provider instances. Emit start, provider, tool, screen, memory, complete, and failed events in sequence order.

- [ ] **Step 5: Register an internal `aiohttp` bridge in `core/http_server.py`.** Add authenticated internal routes under `/xiaozhi/internal/playground/{session_id}` for session creation handoff, input submission, event polling, and close. Protect them with the existing server secret and a configured manager-api allowlist. Keep the bridge disabled unless `playground.enabled` is true in `config.yaml`.

- [ ] **Step 6: Run Python tests and commit.** Run `pytest tests/test_playground_protocol.py tests/test_playground_session.py tests/test_debug_event_instrumentation.py -q`. Expected result is PASS. Commit with `feat(runtime): execute virtual playground sessions`.

### Task 3: Build the companion-console playground and browser-local history

**Files:**
- Create: `server/main/companion-console/src/api/playground.ts`
- Create: `server/main/companion-console/src/api/playground.test.ts`
- Create: `server/main/companion-console/src/pages/playground/playgroundTypes.ts`
- Create: `server/main/companion-console/src/pages/playground/playgroundStorage.ts`
- Create: `server/main/companion-console/src/pages/playground/playgroundStorage.test.ts`
- Create: `server/main/companion-console/src/pages/playground/PlaygroundPage.tsx`
- Create: `server/main/companion-console/src/pages/playground/PlaygroundHistory.tsx`
- Create: `server/main/companion-console/src/pages/playground/PlaygroundConversation.tsx`
- Create: `server/main/companion-console/src/pages/playground/PlaygroundConfiguration.tsx`
- Create: `server/main/companion-console/src/pages/playground/PlaygroundPage.test.tsx`
- Modify: `server/main/companion-console/src/app/navigation.tsx`
- Modify: `server/main/companion-console/src/app/router.tsx`
- Modify: `server/main/companion-console/src/styles.css`

- [ ] **Step 1: Write storage and API tests first.** Storage tests must cover version `xiaozhi.playground.v1`, valid round-trip, malformed JSON recovery, migration of an older version, oldest-session eviction, and omission of audio blobs. API tests must assert URL encoding, request envelopes, SSE event parsing, reconnect cursor handling, and rejection of malformed event payloads.

- [ ] **Step 2: Run `npm test -- --run src/pages/playground/playgroundStorage.test.ts src/api/playground.test.ts` from `server/main/companion-console` and verify failure.** Expected result is missing-module failure.

- [ ] **Step 3: Implement `playgroundTypes.ts` and `playgroundStorage.ts`.** Define `PlaygroundSession`, `PlaygroundMessage`, `PlaygroundEvent`, `PlaygroundSnapshotSummary`, `VirtualDeviceState`, and the versioned root object. Expose `loadPlaygroundStore`, `savePlaygroundStore`, `upsertSession`, `removeSession`, and `migratePlaygroundStore`; sanitize all persisted values to text and metadata.

  Keep persistence narrow and deterministic:

  ```ts
  export const playgroundStorageKey = 'xiaozhi.playground.v1'
  export interface PlaygroundStore { version: 1; sessions: PlaygroundSession[]; activeSessionId: string | null; updatedAt: string }
  function stripAudio(store: PlaygroundStore): PlaygroundStore {
    return JSON.parse(JSON.stringify(store, (key, value) => key === 'audioBlob' ? undefined : value)) as PlaygroundStore
  }
  export function savePlaygroundStore(store: PlaygroundStore) {
    localStorage.setItem(playgroundStorageKey, JSON.stringify(stripAudio(store)))
  }
  ```

- [ ] **Step 4: Implement `api/playground.ts`.** Reuse existing `http` and parser conventions. Add `createPlaygroundSession`, `getPlaygroundSession`, `sendPlaygroundInput`, `streamPlaygroundEvents`, and `closePlaygroundSession`; accept `AbortSignal`, parse the `Result<T>` envelope, and expose only typed non-secret responses.

  Keep the public API typed around the existing envelope and SSE parser:

  ```ts
  interface RequestOptions { signal?: AbortSignal }
  interface StreamOptions extends RequestOptions { onEvent: (event: PlaygroundEvent) => void; onOpen?: () => void }
  export async function createPlaygroundSession(input: PlaygroundSessionCreate, options?: RequestOptions): Promise<PlaygroundSessionCreated>
  export async function sendPlaygroundInput(sessionId: string, input: PlaygroundInput, options?: RequestOptions): Promise<void>
  export async function streamPlaygroundEvents(sessionId: string, after: number, options: StreamOptions): Promise<void>
  ```

- [ ] **Step 5: Implement the fixed three-column page.** Load profiles, model options, voices and skills through existing APIs. Keep local history in the left column; render text and voice composer, event timeline, audio playback, upload control, and virtual screen in the center; render profile, prompts, model bindings, voice, skills, single-capability test buttons, and virtual-device dimensions in the right column. Mark edits as unapplied until the user creates or applies a new snapshot. Use Ant Design controls and existing surface tokens; keep semantic labels and keyboard focus states.

  Keep the page composition explicit and testable:

  ```tsx
  return <PageContainer className="playground-page">
    <div className="playground-grid">
      <PlaygroundHistory ... />
      <PlaygroundConversation ... />
      <PlaygroundConfiguration ... />
    </div>
  </PageContainer>
  ```

- [ ] **Step 6: Add event-driven behavior.** On session creation, persist the local record only after the server returns a session id. On input, append an optimistic user message, stream events, update assistant output and screen state, and persist metadata after each event. On reconnect, resume from the last cursor. On failure, keep the conversation usable and attach a retry action to the failed event.

- [ ] **Step 7: Register route and verify UI tests.** Add `playground` route metadata with workbench group and normal-user permission, lazy-load `PlaygroundPage`, and add layout styles for desktop and narrow widths. Run `npm test -- --run src/pages/playground src/app/navigation.test.tsx src/app/router.test.tsx`, then `npm run lint` and `npm run build`. Commit with `feat(console): add virtual playground panel`.

### Task 4: Cross-boundary verification and release evidence

**Files:**
- Create: `server/main/companion-console/e2e/playground.spec.ts`
- Create: `server/main/manager-api/src/test/java/xiaozhi/modules/companion/playground/PlaygroundSecurityIntegrationTest.java`
- Create: `server/main/xiaozhi-server/tests/test_playground_integration.py`
- Modify: `docs/superpowers/specs/2026-08-22-playground-design.md`

- [ ] **Step 1: Add the end-to-end browser journey.** Start with a mocked catalog and runtime event stream, open `/playground`, create a session, send text, switch to voice mode, upload an image, set an activity state, retry a failed TTS event, reload the page, and assert the local history restores messages and event metadata without an audio Blob.

  The reload assertion must inspect the actual browser storage value:

  ```ts
  await page.reload()
  await expect(page.getByText('视觉理解')).toBeVisible()
  const stored = await page.evaluate(() => localStorage.getItem('xiaozhi.playground.v1'))
  expect(stored).not.toContain('audioBlob')
  ```

- [ ] **Step 2: Add manager-api security integration coverage.** Assert cross-user profile access returns the project’s authorization error, secret-bearing fields are rejected, expired sessions return a typed error, and closing a session removes its event stream without touching device or memory repositories.

- [ ] **Step 3: Add the Python integration fixture.** Feed one snapshot through text, ASR, LLM, TTS, vision, activity, tool, and memory stages; assert monotonically increasing event sequence, redacted summaries, screen-state updates, and temporary-memory-only writes.

- [ ] **Step 4: Run the complete verification set.** Run `./gradlew :manager-api:test`, `pytest -q` in `server/main/xiaozhi-server`, and `npm test && npm run lint && npm run build` in `server/main/companion-console`. Run `npm run test:e2e -- e2e/playground.spec.ts` with the configured test services. Expected result is PASS for every command.

- [ ] **Step 5: Perform visual and responsive checks.** Capture desktop and narrow screenshots, inspect loading, empty, error, disabled, focus, hover, and overflow states, and verify no secret or audio payload appears in browser storage, SSE payloads, or logs. Record artifact paths and the runtime feature flag in the release evidence.

- [ ] **Step 6: Commit verification evidence.** Update the design document only with observed contract or test evidence, then commit with `test: verify virtual playground flow`.
