import 'dart:async';
import 'dart:typed_data';

import 'package:ai_plush_companion/features/audio/data/audio_capture_controller.dart';
import 'package:ai_plush_companion/features/audio/data/audio_turn_sender.dart';
import 'package:ai_plush_companion/features/audio/domain/audio_models.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeCapture implements AudioCapture {
  AudioFrameHandler? handler;
  bool running = false;
  final startGate = Completer<void>();
  bool gateStart = false;
  bool throwOnStart = false;
  bool throwOnStop = false;

  @override
  bool get isRunning => running;

  @override
  Future<bool> start({
    PcmFrameHandler? onFrame,
    AudioFrameHandler? onAudioFrame,
  }) async {
    handler = onAudioFrame;
    running = true;
    if (throwOnStart) throw StateError('recorder failed');
    if (gateStart) await startGate.future;
    return true;
  }

  void emit(int byteLength, int durationMs) {
    handler?.call(
      AudioFrame(
        Uint8List.fromList(List<int>.filled(byteLength, 4)),
        durationMs: durationMs,
      ),
    );
  }

  @override
  Future<void> stop() async {
    running = false;
    if (throwOnStop) throw StateError('stop failed');
  }

  @override
  Future<void> dispose() async {}
}

class _FakeTransport implements AudioTurnTransport {
  bool connected = true;
  final calls = <String>[];
  final chunks = <Uint8List>[];

  @override
  bool get isConnected => connected;

  @override
  void startAudio(
    String requestId, {
    required int durationMs,
    required String mimeType,
  }) {
    calls.add('start:$requestId:$durationMs:$mimeType');
  }

  @override
  void pushAudio(Uint8List bytes) => chunks.add(bytes);

  @override
  void endAudio(String requestId) => calls.add('end:$requestId');
}

void main() {
  test('sends one complete binary audio turn on release', () async {
    final capture = _FakeCapture();
    final transport = _FakeTransport();
    var id = 0;
    final sender = AudioTurnSender(
      capture: capture,
      transport: transport,
      requestId: () => 'request-${++id}',
    );

    expect(await sender.start(), isTrue);
    capture.emit(320, 10);
    capture.emit(160, 5);
    final result = await sender.stop();

    expect(result?.requestId, 'request-1');
    expect(result?.durationMs, 15);
    expect(transport.calls, ['start:request-1:15:audio/pcm', 'end:request-1']);
    expect(transport.chunks.map((chunk) => chunk.length), [320, 160]);
  });

  test('upward cancellation stops capture without sending a turn', () async {
    final capture = _FakeCapture();
    final transport = _FakeTransport();
    final sender = AudioTurnSender(capture: capture, transport: transport);

    await sender.start();
    capture.emit(320, 10);
    expect(await sender.stop(cancelled: true), isNull);
    expect(transport.calls, isEmpty);
    expect(transport.chunks, isEmpty);
    expect(capture.running, isFalse);
  });

  test('does not send when the socket is disconnected', () async {
    final capture = _FakeCapture();
    final transport = _FakeTransport()..connected = false;
    final sender = AudioTurnSender(capture: capture, transport: transport);

    await sender.start();
    capture.emit(320, 10);
    expect(await sender.stop(), isNull);
    expect(transport.calls, isEmpty);
  });

  test(
    'coalesces concurrent starts and waits for the recorder before stopping',
    () async {
      final capture = _FakeCapture()..gateStart = true;
      final transport = _FakeTransport();
      final sender = AudioTurnSender(capture: capture, transport: transport);

      final first = sender.start();
      final second = sender.start();
      expect(identical(first, second), isTrue);
      capture.startGate.complete();
      expect(await first, isTrue);
      capture.emit(320, 10);
      await sender.stop();
      expect(transport.calls, hasLength(2));
    },
  );

  test('resets recording state when the recorder fails to start', () async {
    final capture = _FakeCapture()..throwOnStart = true;
    final sender = AudioTurnSender(
      capture: capture,
      transport: _FakeTransport(),
    );

    expect(sender.start(), throwsA(isA<StateError>()));
    await Future<void>.delayed(Duration.zero);
    expect(sender.isRecording, isFalse);
  });

  test('does not retain buffered frames when recorder stop fails', () async {
    final capture = _FakeCapture()..throwOnStop = true;
    final sender = AudioTurnSender(
      capture: capture,
      transport: _FakeTransport(),
    );

    await sender.start();
    capture.emit(320, 10);
    expect(sender.stop(), throwsA(isA<StateError>()));
    await Future<void>.delayed(Duration.zero);
    expect(sender.isRecording, isFalse);
    expect(sender.byteLength, 0);
  });

  test('caps a recording at the server sixty-second limit', () async {
    final capture = _FakeCapture();
    final transport = _FakeTransport();
    final sender = AudioTurnSender(
      capture: capture,
      transport: transport,
      maxDurationMs: 20,
    );

    await sender.start();
    capture.emit(640, 20);
    capture.emit(640, 20);
    final result = await sender.stop();

    expect(result?.durationMs, 20);
    expect(result?.byteLength, lessThanOrEqualTo(640));
    expect(transport.chunks, hasLength(1));
  });
}
