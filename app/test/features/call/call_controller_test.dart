import 'dart:async';
import 'dart:typed_data';

import 'package:ai_plush_companion/features/audio/data/audio_capture_controller.dart';
import 'package:ai_plush_companion/features/audio/data/audio_playback_controller.dart';
import 'package:ai_plush_companion/features/audio/application/voice_activity_segmenter.dart';
import 'package:ai_plush_companion/features/audio/domain/audio_models.dart';
import 'package:ai_plush_companion/features/audio/domain/playback_state.dart';
import 'package:ai_plush_companion/features/call/application/call_controller.dart';
import 'package:ai_plush_companion/features/call/application/call_transport.dart';
import 'package:ai_plush_companion/features/call/domain/call_models.dart';
import 'package:ai_plush_companion/features/chat/domain/realtime_models.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeTransport implements CallTransport {
  final eventsController = StreamController<RealtimeEvent>.broadcast();
  final pushed = <Uint8List>[];
  final commits = <({String requestId, int durationMs})>[];
  final cancels = <({String turnId, int playedMs})>[];
  bool connected = false;
  bool stopped = false;
  bool closed = false;
  Map<String, dynamic>? format;

  @override
  Stream<RealtimeEvent> get events => eventsController.stream;

  @override
  bool get isConnected => connected;

  @override
  Future<void> connect({
    String? conversationId,
    String? profileId,
    Map<String, dynamic>? format,
  }) async {
    connected = true;
    this.format = format;
  }

  @override
  void pushAudio(Uint8List bytes) => pushed.add(Uint8List.fromList(bytes));

  @override
  void commitAudio(String requestId, int durationMs) =>
      commits.add((requestId: requestId, durationMs: durationMs));

  @override
  void cancel(String turnId, int playedMs) =>
      cancels.add((turnId: turnId, playedMs: playedMs));

  @override
  void heartbeat() {}

  @override
  void stop() => stopped = true;

  @override
  Future<void> close() async {
    closed = true;
    connected = false;
  }

  Future<void> dispose() => eventsController.close();
}

class _FakeCapture implements AudioCapture {
  AudioFrameHandler? handler;
  bool running = false;
  bool permission = true;
  bool throwOnStart = false;
  int starts = 0;
  int stops = 0;

  @override
  bool get isRunning => running;

  @override
  Future<bool> start({
    PcmFrameHandler? onFrame,
    AudioFrameHandler? onAudioFrame,
  }) async {
    starts++;
    if (!permission) return false;
    if (throwOnStart) throw StateError('recorder failed');
    handler = onAudioFrame;
    running = true;
    return true;
  }

  void emit(Uint8List bytes, int durationMs) =>
      handler?.call(AudioFrame(bytes, durationMs: durationMs));

  @override
  Future<void> stop() async {
    stops++;
    running = false;
  }

  @override
  Future<void> dispose() async {}
}

class _FakePlayback implements CallPlayback {
  final queued = <PlaybackItem>[];
  @override
  int playedMilliseconds = 321;
  @override
  PlaybackStatus status = PlaybackStatus.idle;
  int stopCount = 0;

  @override
  Future<void> enqueue(PlaybackItem item) async {
    queued.add(item);
    status = PlaybackStatus.playing;
  }

  @override
  Future<void> stop() async {
    stopCount++;
    status = PlaybackStatus.idle;
  }
}

class _ImmediateCallOutput implements AudioOutput {
  @override
  Future<void> play(PlaybackItem item) async {}

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async {}
}

class _BlockingPlayback implements CallPlayback {
  final queued = <String>[];
  final firstStarted = Completer<void>();
  final releaseFirst = Completer<void>();
  int inFlight = 0;
  int maxInFlight = 0;

  @override
  int playedMilliseconds = 0;

  @override
  PlaybackStatus status = PlaybackStatus.idle;

  @override
  Future<void> enqueue(PlaybackItem item) async {
    inFlight++;
    if (inFlight > maxInFlight) maxInFlight = inFlight;
    if (!firstStarted.isCompleted) firstStarted.complete();
    if (queued.isEmpty) await releaseFirst.future;
    queued.add(item.id);
    inFlight--;
    status = PlaybackStatus.playing;
  }

  @override
  Future<void> stop() async {
    status = PlaybackStatus.idle;
  }
}

Uint8List pcm(double amplitude, [int samples = 160]) {
  final bytes = ByteData(samples * 2);
  final value = (amplitude * 32767).round();
  for (var i = 0; i < samples; i++) {
    bytes.setInt16(i * 2, value, Endian.little);
  }
  return bytes.buffer.asUint8List();
}

void main() {
  late _FakeTransport transport;
  late _FakeCapture capture;
  late _FakePlayback playback;
  late CallController controller;

  setUp(() {
    transport = _FakeTransport();
    capture = _FakeCapture();
    playback = _FakePlayback();
    controller = CallController(
      transport: transport,
      capture: capture,
      playback: playback,
      segmenter: VoiceActivitySegmenter(
        calibrationFrames: 1,
        onsetFrames: 1,
        hangoverMs: 20,
      ),
      requestId: () => 'request-1',
    );
  });

  tearDown(() async {
    await controller.end();
    await transport.dispose();
    controller.dispose();
  });

  test(
    'configures the platform audio session on start and releases it on end',
    () async {
      var configureCalls = 0;
      var releaseCalls = 0;
      final sessionController = CallController(
        transport: transport,
        capture: capture,
        playback: playback,
        configureAudioSession: () async {
          configureCalls++;
          return true;
        },
        deactivateAudioSession: () async => releaseCalls++,
      );

      await sessionController.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      expect(configureCalls, 1);
      await sessionController.end();
      expect(releaseCalls, 1);
      sessionController.dispose();
    },
  );

  test('pauses and resumes capture for system audio interruptions', () async {
    final interruptions = StreamController<bool>.broadcast();
    final sessionController = CallController(
      transport: transport,
      capture: capture,
      playback: playback,
      audioInterruptions: interruptions.stream,
    );
    await sessionController.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    interruptions.add(true);
    await Future<void>.delayed(Duration.zero);
    expect(sessionController.state, CallState.reconnecting);
    expect(capture.running, isFalse);
    interruptions.add(false);
    await Future<void>.delayed(Duration.zero);
    expect(sessionController.state, CallState.listening);
    expect(capture.starts, 2);
    await interruptions.close();
    await sessionController.end();
    sessionController.dispose();
  });

  test('ends an active call from the system media stop action', () async {
    final stopRequests = StreamController<void>.broadcast();
    final sessionController = CallController(
      transport: transport,
      capture: capture,
      playback: playback,
      stopRequests: stopRequests.stream,
    );
    await sessionController.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );

    stopRequests.add(null);
    await Future<void>.delayed(Duration.zero);

    expect(sessionController.state, CallState.ended);
    expect(transport.stopped, isTrue);
    await stopRequests.close();
    sessionController.dispose();
  });

  test('connects one capture stream and commits a VAD segment', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    expect(controller.state, CallState.listening);
    expect(capture.starts, 1);
    expect(transport.format, {
      'format': 'pcm_s16le',
      'sample_rate': 16000,
      'channels': 1,
    });

    capture.emit(pcm(.001), 20);
    capture.emit(pcm(.3), 20);
    capture.emit(pcm(.001), 20);

    expect(transport.pushed, hasLength(3));
    expect(transport.commits, hasLength(1));
    expect(transport.commits.single.durationMs, 40);
    expect(controller.state, CallState.thinking);
  });

  test(
    'interrupts assistant speech with the active turn and clears playback',
    () async {
      await controller.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      capture.emit(pcm(.001), 20);
      transport.eventsController.add(
        const RealtimeJsonEvent(
          type: 'turn.started',
          sequence: 1,
          turnId: 'turn-1',
        ),
      );
      transport.eventsController.add(
        RealtimeAudioEvent(
          type: 'tts.audio',
          sequence: 2,
          turnId: 'turn-1',
          bytes: Uint8List.fromList([1, 2]),
          details: {'mime_type': 'audio/wav'},
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(controller.state, CallState.speaking);
      expect(playback.queued, hasLength(1));

      capture.emit(pcm(.3), 20);
      expect(transport.cancels, [(turnId: 'turn-1', playedMs: 321)]);
      expect(playback.stopCount, 1);
      expect(controller.state, CallState.listening);
    },
  );

  test('ignores late TTS bytes from a turn that was interrupted', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    capture.emit(pcm(.001), 20);
    transport.eventsController.add(
      const RealtimeJsonEvent(
        type: 'turn.started',
        sequence: 1,
        turnId: 'turn-1',
      ),
    );
    transport.eventsController.add(
      RealtimeAudioEvent(
        type: 'tts.audio',
        sequence: 2,
        turnId: 'turn-1',
        bytes: Uint8List.fromList([1]),
      ),
    );
    await Future<void>.delayed(Duration.zero);
    capture.emit(pcm(.3), 20);
    await Future<void>.delayed(Duration.zero);
    transport.eventsController.add(
      RealtimeAudioEvent(
        type: 'tts.audio',
        sequence: 3,
        turnId: 'turn-1',
        bytes: Uint8List.fromList([2]),
      ),
    );
    await Future<void>.delayed(Duration.zero);
    expect(playback.queued, hasLength(1));
  });

  test(
    'mute keeps the transport connected while suppressing uploads',
    () async {
      await controller.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      controller.toggleMute();
      capture.emit(pcm(.3), 20);
      expect(controller.state, CallState.muted);
      expect(transport.pushed, isEmpty);
      expect(transport.connected, isTrue);
      controller.toggleMute();
      expect(controller.state, CallState.listening);
    },
  );

  test(
    'microphone denial fails without fabricating a connected call',
    () async {
      capture.permission = false;
      await controller.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      expect(controller.state, CallState.failed);
      expect(capture.starts, 1);
      expect(transport.closed, isTrue);
    },
  );

  test('does not commit the same VAD segment twice', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    capture.emit(pcm(.001), 20);
    capture.emit(pcm(.3), 20);
    capture.emit(pcm(.001), 20);
    capture.emit(pcm(.001), 20);
    expect(transport.commits, hasLength(1));
  });

  test(
    'pauses capture on transport failure and can resume without reconnecting',
    () async {
      await controller.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      transport.eventsController.addError(StateError('socket lost'));
      await Future<void>.delayed(Duration.zero);
      expect(controller.state, CallState.reconnecting);
      expect(capture.running, isFalse);
      await controller.resumeAfterInterruption();
      expect(controller.state, CallState.listening);
      expect(capture.starts, 2);
    },
  );

  test('reports a recorder error while resuming an interrupted call', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    await controller.pauseForInterruption();
    capture.throwOnStart = true;
    await controller.resumeAfterInterruption();
    expect(controller.state, CallState.failed);
    expect(controller.error, contains('recorder failed'));
  });

  test('closes capture and playback when the runtime expires', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    transport.eventsController.add(
      const RealtimeJsonEvent(type: 'session.expired', sequence: 1),
    );
    await Future<void>.delayed(Duration.zero);
    expect(controller.state, CallState.failed);
    expect(capture.running, isFalse);
    expect(playback.stopCount, 1);
    expect(transport.closed, isTrue);
  });

  test('surfaces a streaming turn failure and stops the active call', () async {
    await controller.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );

    transport.eventsController.add(
      const RealtimeJsonEvent(
        type: 'turn.failed',
        sequence: 1,
        turnId: 'turn-1',
        details: {'message': 'TTS provider failed'},
      ),
    );
    await Future<void>.delayed(Duration.zero);

    expect(controller.state, CallState.failed);
    expect(controller.error, contains('TTS provider failed'));
    expect(capture.running, isFalse);
  });

  test('returns to listening when the current audio item completes', () async {
    final concretePlayback = AudioPlaybackController(
      output: _ImmediateCallOutput(),
    );
    final sessionController = CallController(
      transport: transport,
      capture: capture,
      playback: concretePlayback,
    );
    await sessionController.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );
    transport.eventsController.add(
      const RealtimeJsonEvent(
        type: 'turn.started',
        sequence: 1,
        turnId: 'turn-1',
      ),
    );
    transport.eventsController.add(
      RealtimeAudioEvent(
        type: 'tts.audio',
        sequence: 2,
        turnId: 'turn-1',
        bytes: Uint8List.fromList([1, 2]),
      ),
    );
    await Future<void>.delayed(Duration.zero);
    expect(sessionController.state, CallState.speaking);

    await concretePlayback.completeCurrent();
    expect(sessionController.state, CallState.listening);

    await sessionController.end();
    sessionController.dispose();
  });

  test(
    'does not replace an active call when runtime loading is requested',
    () async {
      await controller.start(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );
      await controller.startFromRuntime(
        conversationId: 'conversation-2',
        runtime: () async => {
          'streamUrl': 'wss://other.test',
          'runtimeToken': 'other-token',
        },
      );
      expect(controller.conversationId, 'conversation-1');
      expect(controller.state, CallState.listening);
    },
  );

  test(
    'starts a call from runtime metadata for the same conversation',
    () async {
      await controller.startFromRuntime(
        conversationId: 'conversation-1',
        runtime: () async => {
          'streamUrl': 'wss://example.test',
          'runtimeToken': 'token',
        },
      );
      expect(controller.state, CallState.listening);
      expect(controller.conversationId, 'conversation-1');
    },
  );

  test(
    'rewrites the runtime websocket origin before starting a call',
    () async {
      final factoryTransport = _FakeTransport();
      final factoryCapture = _FakeCapture();
      Uri? seenUrl;
      final originController = CallController(
        runtimeWsOrigin: Uri.parse('https://edge.example.test:9443'),
        transportFactory: (url, _) {
          seenUrl = url;
          return factoryTransport;
        },
        capture: factoryCapture,
        playback: _FakePlayback(),
      );

      await originController.start(
        streamUrl: Uri.parse('ws://10.0.0.8:8000/xiaozhi/v1/'),
        runtimeToken: 'token',
        conversationId: 'conversation-1',
      );

      expect(seenUrl, Uri.parse('wss://edge.example.test:9443/xiaozhi/v1/'));
      await originController.end();
      originController.dispose();
      await factoryTransport.dispose();
    },
  );

  test('serializes asynchronous realtime event handling', () async {
    final serialPlayback = _BlockingPlayback();
    final serialController = CallController(
      transport: transport,
      capture: capture,
      playback: serialPlayback,
    );
    await serialController.start(
      streamUrl: Uri.parse('wss://example.test'),
      runtimeToken: 'token',
      conversationId: 'conversation-1',
    );

    transport.eventsController.add(
      const RealtimeJsonEvent(
        type: 'turn.started',
        sequence: 1,
        turnId: 'turn-serial',
      ),
    );
    transport.eventsController.add(
      RealtimeAudioEvent(
        type: 'tts.audio',
        sequence: 2,
        turnId: 'turn-serial',
        bytes: Uint8List.fromList([1]),
      ),
    );
    transport.eventsController.add(
      RealtimeAudioEvent(
        type: 'tts.audio',
        sequence: 3,
        turnId: 'turn-serial',
        bytes: Uint8List.fromList([2]),
      ),
    );

    await serialPlayback.firstStarted.future;
    await Future<void>.delayed(Duration.zero);
    expect(serialPlayback.maxInFlight, 1);
    serialPlayback.releaseFirst.complete();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(serialPlayback.queued, ['turn-serial-2', 'turn-serial-3']);
    await serialController.end();
    serialController.dispose();
  });
}
