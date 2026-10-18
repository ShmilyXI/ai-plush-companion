# Flutter AI Companion Conversation and Audio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the Flutter chat UI to the public WebSocket and deliver press-to-send voice, background reply playback, and full-screen continuous voice calls with interruption.

**Architecture:** A single `ConversationRealtimeClient` owns one WebSocket and normalizes JSON events plus binary TTS frames. `ConversationController` owns turn state, while `AudioCaptureController`, `VoiceActivitySegmenter`, and `AudioPlaybackController` own capture, segmentation, and playback respectively. A call has a separate state machine but reuses the selected persistent conversation and runtime renewal API.

**Tech Stack:** `web_socket_channel`, `record`, `just_audio`, `audio_service`, `audio_session`, `permission_handler`, `path_provider`, `uuid`, Flutter platform channels for Android foreground service and iOS audio-session lifecycle.

---

## File map

Conversation transport belongs in `app/lib/features/chat/data/`; reusable audio primitives belong in `app/lib/features/audio/`; call UI and state belong in `app/lib/features/call/`. The audio service must not know about widgets or profile selection. Every transport event carries conversation, turn, request and sequence identifiers so reconnects can be deduplicated.

## Task 1: Implement the WebSocket event adapter

**Files:**

- Create: `app/lib/features/chat/domain/realtime_models.dart`
- Create: `app/lib/features/chat/data/conversation_realtime_client.dart`
- Create: `app/lib/features/chat/data/realtime_event_decoder.dart`
- Modify: `app/lib/features/chat/application/conversation_controller.dart`
- Create: `app/test/features/chat/realtime_event_decoder_test.dart`
- Create: `app/test/features/chat/conversation_realtime_client_test.dart`

- [ ] **Step 1: Write decoder tests for JSON and binary pairing.** Cover `session.ready`, `stream.ready`, `asr.partial`, `asr.final`, `llm.delta`, `tts.audio`, `turn.interrupted`, `turn.completed`, `error`, `session.expiring`, `session.expired`, and an adjacent binary frame paired with a `transport:"binary"` TTS metadata event. Reject malformed JSON, non-increasing sequence numbers and an unpaired binary frame.

- [ ] **Step 2: Implement typed event decoding.** Convert all `details` values into immutable model classes and preserve unknown event types as `RealtimeUnknownEvent` for forward compatibility. Keep the raw audio bytes out of logs.

- [ ] **Step 3: Write client lifecycle tests.** Assert that opening sends the bearer subprotocol and `web.session.start` payload, text sends `turn.text`, audio commits send `input.audio.commit`, cancellation sends `response.cancel` with played milliseconds, heartbeat includes the last sequence, and `stream.stop` is sent exactly once.

- [ ] **Step 4: Implement `ConversationRealtimeClient`.** Use `WebSocketChannel.connect` with `protocols: ['bearer.$runtimeToken']`, `binaryType` handling, a bounded outgoing queue while connecting, a single incoming event stream, and an explicit `close(reason)` method. Do not auto-resend a turn after reconnect.

```dart
class ConversationRealtimeClient {
  ConversationRealtimeClient({required this.streamUrl, required this.runtimeToken});
  final Uri streamUrl;
  final String runtimeToken;

  Stream<RealtimeEvent> get events => _events.stream;
  void sendText(String requestId, String text) => _send({'type': 'turn.text', 'request_id': requestId, 'text': text});
  void commitAudio(String requestId, int durationMs) => _send({'type': 'input.audio.commit', 'request_id': requestId, 'duration_ms': durationMs});
  void cancel(String turnId, int playedMs) => _send({'type': 'response.cancel', 'turn_id': turnId, 'played_ms': playedMs});
  void pushPcm(Uint8List bytes) => _channel.sink.add(bytes.buffer);
}
```

- [ ] **Step 5: Connect the client to `ConversationController`.** Keep active runtime metadata, last server sequence, active turn IDs and connection state in the controller; close the client when the selected profile or persistent conversation changes.

- [ ] **Step 6: Run transport tests.**

```bash
cd app
flutter test test/features/chat/realtime_event_decoder_test.dart test/features/chat/conversation_realtime_client_test.dart test/features/chat/conversation_controller_test.dart
```

- [ ] **Step 7: Commit the transport slice.**

```bash
git add app/lib/features/chat app/test/features/chat
git commit -m "feat: add Flutter conversation websocket client"
```

## Task 2: Implement capture and press-to-send voice

**Files:**

- Create: `app/lib/features/audio/domain/audio_models.dart`
- Create: `app/lib/features/audio/data/audio_capture_controller.dart`
- Create: `app/lib/features/audio/application/voice_activity_segmenter.dart`
- Modify: `app/lib/features/chat/presentation/message_composer.dart`
- Create: `app/test/features/audio/voice_activity_segmenter_test.dart`
- Create: `app/test/features/audio/audio_capture_controller_test.dart`
- Create: `app/test/features/chat/message_composer_voice_test.dart`

- [ ] **Step 1: Write deterministic VAD tests.** Feed synthetic PCM frames with quiet noise, speech, trailing silence and a new speech onset. Assert that the segmenter calibrates a noise floor, starts only after the configured speech threshold, commits after the hangover window, and emits an interruption signal when speech starts during playback. A single short noise burst must not commit a turn.

- [ ] **Step 2: Implement `VoiceActivitySegmenter`.** Use 16-bit little-endian PCM RMS plus a calibrated noise floor, a speech onset window, a 700 ms silence hangover and a maximum segment duration. Expose `push(frame)`, `reset()`, `commitIfActive()`, and `cancel()`; keep thresholds injectable for tests.

- [ ] **Step 3: Implement `AudioCaptureController`.** Request microphone permission only on start, call `AudioRecorder.startStream` with 16 kHz, mono, `pcm16bits`, and forward immutable frame copies to the segmenter and realtime client. Stop and dispose the recorder on every terminal path.

- [ ] **Step 4: Implement press gesture semantics.** `MessageComposer` starts capture on long press, tracks vertical drag distance, sends on release when not cancelled, and discards frames on upward cancellation. Display elapsed duration, amplitude and the cancellation threshold without changing the composer height.

- [ ] **Step 5: Add interruption handling.** If the segmenter detects speech while a response is playing, call `ConversationRealtimeClient.cancel(activeTurnId, playedMs)`, clear the playback controller, then continue capturing the new segment. Do not mark the interrupted assistant turn completed.

- [ ] **Step 6: Run capture tests.**

```bash
cd app
flutter test test/features/audio test/features/chat/message_composer_voice_test.dart
```

- [ ] **Step 7: Commit press-to-send voice.**

```bash
git add app/lib/features/audio app/lib/features/chat/presentation/message_composer.dart app/test/features/audio app/test/features/chat/message_composer_voice_test.dart
git commit -m "feat: add press to send voice input"
```

## Task 3: Implement one-queue reply playback and historical replay

**Files:**

- Create: `app/lib/features/audio/data/audio_playback_controller.dart`
- Create: `app/lib/features/audio/data/companion_audio_handler.dart`
- Create: `app/lib/features/audio/domain/playback_state.dart`
- Modify: `app/lib/features/chat/application/conversation_controller.dart`
- Modify: `app/lib/features/chat/presentation/reply_audio_button.dart`
- Create: `app/test/features/audio/audio_playback_controller_test.dart`
- Create: `app/test/features/audio/companion_audio_handler_test.dart`

- [ ] **Step 1: Write queue tests.** Assert that a new assistant audio item stops and removes the previous item, manual playback of a historical turn requests the short-lived audio endpoint, pause/resume preserves position, stop clears the queue, and a failed item advances without blocking later items.

- [ ] **Step 2: Implement temporary audio materialization.** Decode `audio/wav` or `audio/opus` bytes from a TTS event, write them to a unique file under the app cache directory, enqueue the file, and delete it after completion or cancellation. If the server sends sentence-level `tts.audio.chunk`, treat each complete chunk as one queue item; never concatenate arbitrary encoded frames.

- [ ] **Step 3: Implement `CompanionAudioHandler`.** Wrap one `AudioPlayer` in an `AudioHandler`, publish media controls and playback state, expose custom actions `pause`, `resume`, `stop`, and `returnToConversation`, and keep notification text free of message contents.

- [ ] **Step 4: Configure audio focus and interruptions.** Use `audio_session` to request speech/media playback focus, pause on phone calls or another app's exclusive audio, and resume only when the system reports the session active. Do not resend the originating turn.

- [ ] **Step 5: Wire automatic and manual playback.** When global `autoPlay` is true, enqueue new response audio; otherwise render a play button on every assistant message. Store `autoPlay` only in `shared_preferences`, never in the profile payload.

- [ ] **Step 6: Add background platform declarations.** Register the Android audio foreground service and notification channel, and enable iOS background audio plus the required `AVAudioSession` category. Limit the service to playback until the call plan adds microphone capture.

- [ ] **Step 7: Run playback tests and a debug build.**

```bash
cd app
flutter test test/features/audio
flutter build apk --debug --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
flutter build ios --no-codesign --dart-define=API_BASE_URL=https://example.invalid/xiaozhi
```

- [ ] **Step 8: Commit reply playback.**

```bash
git add app/lib/features/audio app/lib/features/chat app/android app/ios app/test/features/audio
git commit -m "feat: add background reply audio playback"
```

## Task 4: Implement full-screen continuous voice calls

**Files:**

- Create: `app/lib/features/call/domain/call_models.dart`
- Create: `app/lib/features/call/application/call_controller.dart`
- Create: `app/lib/features/call/presentation/full_screen_call_page.dart`
- Create: `app/lib/features/call/presentation/call_status_view.dart`
- Create: `app/lib/features/audio/data/call_audio_session.dart`
- Modify: `app/lib/features/chat/presentation/chat_page.dart`
- Modify: `app/lib/core/routing/app_router.dart`
- Create: `app/test/features/call/call_controller_test.dart`
- Create: `app/test/features/call/full_screen_call_page_test.dart`

- [ ] **Step 1: Define the call state machine.** Use `idle`, `preparing`, `connecting`, `listening`, `thinking`, `speaking`, `muted`, `reconnecting`, `ending`, `ended`, and `failed`. Invariants are one capture stream, one realtime client, one playback queue, and at most one active turn.

- [ ] **Step 2: Write controller tests.** Cover entering from an existing conversation, runtime renewal for the same conversation, microphone denial, automatic VAD commit, mute without disconnect, speech interruption during TTS, WebSocket reconnect without duplicate commit, lock-screen lifecycle events, and hang-up returning to the original chat route.

- [ ] **Step 3: Implement call preparation.** Check or request microphone permission, configure `AudioSession` for play-and-record with voice communication mode, renew the selected conversation runtime when expired, and open `web.session.start` with `{format:"pcm_s16le",sample_rate:16000,channels:1}`.

- [ ] **Step 4: Implement continuous capture.** Keep the recorder stream alive while the call is active; feed frames to `VoiceActivitySegmenter`, commit after silence, and continue listening while the assistant speaks. On speech onset during playback, cancel with measured `playedMs` and clear only local audio output.

- [ ] **Step 5: Build the full-screen call page.** Show the selected role avatar/name, elapsed time, live transcript, current state, and stable controls for mute, speaker route, and hang up. A muted call keeps the WebSocket and conversation context; hang-up sends `stream.stop`, closes capture, and returns to chat.

- [ ] **Step 6: Add background call lifecycle.** On Android start a foreground service with microphone and data-sync service types; on iOS keep the `AVAudioSession` active with the background audio capability. Route notification actions to the controller through a platform bridge. Force-closing the app ends the call and marks it incomplete rather than fabricating a completed turn.

- [ ] **Step 7: Handle lock-screen and system interruptions.** Pause capture on permission revocation, Bluetooth route loss, phone calls or system audio interruption; show `reconnecting` or `muted` state and resume only after the platform reports a valid route. Preserve the same persistent conversation ID.

- [ ] **Step 8: Run call tests and platform integration tests.**

```bash
cd app
flutter test test/features/call
flutter test integration_test/call_lifecycle_test.dart -d emulator-5554
flutter test integration_test/call_lock_screen_test.dart -d "iPhone 15"
```

Expected: controller/widget tests pass; device tests must prove mute, hang-up, audio interruption and no duplicate turns. A simulator cannot prove microphone quality, so record a real-device run for final acceptance.

- [ ] **Step 9: Commit full-screen calls.**

```bash
git add app/lib/features/call app/lib/features/audio app/lib/features/chat app/lib/core/routing app/android app/ios app/test/features/call app/integration_test
git commit -m "feat: add full screen realtime voice calls"
```

## Task 5: Close the audio contract and run end-to-end checks

**Files:**

- Modify: `docs/public-conversation-api.yaml`
- Modify: `server/main/xiaozhi-server/core/public_conversation/protocol.py`
- Modify: `server/main/xiaozhi-server/core/api/public_conversation_handler.py`
- Modify: `server/main/xiaozhi-server/tests/test_public_conversation_streaming_protocol.py`
- Create: `server/main/xiaozhi-server/tests/test_public_conversation_call_contract.py`
- Create: `app/integration_test/conversation_audio_contract_test.dart`

- [ ] **Step 1: Document sentence-level TTS boundaries.** Require each `tts.audio` or `tts.audio.chunk` event paired with binary data to carry `mime_type`, `byte_length`, `audio_sequence`, `turn_id`, and a complete decodable audio segment. State that clients must not concatenate unrelated encoded chunks.

- [ ] **Step 2: Add server contract tests.** Assert continuous start, PCM frame validation, commit, interruption, binary TTS pairing, heartbeat, expiry and stream stop remain compatible with the existing `companion-web` client.

- [ ] **Step 3: Add Flutter contract tests with a fake server.** Simulate a complete text turn, press-to-send audio turn, TTS playback, interrupted call and runtime renewal; assert the same conversation ID and exactly one durable turn per committed request.

- [ ] **Step 4: Run the complete audio verification set.**

```bash
cd server/main/xiaozhi-server
python -m pytest tests/test_public_conversation_streaming_protocol.py tests/test_public_conversation_call_contract.py tests/test_public_conversation_http.py -q
cd ../../../app
flutter test test/features/chat test/features/audio test/features/call integration_test/conversation_audio_contract_test.dart
flutter analyze
```

- [ ] **Step 5: Commit the contract slice.**

```bash
git add docs/public-conversation-api.yaml server/main/xiaozhi-server/core/public_conversation server/main/xiaozhi-server/core/api/public_conversation_handler.py server/main/xiaozhi-server/tests/test_public_conversation_streaming_protocol.py server/main/xiaozhi-server/tests/test_public_conversation_call_contract.py app/integration_test/conversation_audio_contract_test.dart
git commit -m "test: lock mobile realtime audio contract"
```

## Audio gate

Do not claim voice support until Android and iOS each demonstrate a real microphone permission grant, a committed turn, a response interruption, background reply playback, lock-screen call continuation, notification hang-up, and no duplicate durable history row. A green Flutter test suite alone is not evidence for these platform behaviors.
