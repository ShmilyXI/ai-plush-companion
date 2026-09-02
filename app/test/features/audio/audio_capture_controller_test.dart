import 'dart:async';
import 'dart:typed_data';

import 'package:ai_plush_companion/features/audio/data/audio_capture_controller.dart';
import 'package:ai_plush_companion/features/audio/domain/audio_models.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeRecorderSource implements AudioRecorderSource {
  final frames = StreamController<Uint8List>.broadcast();
  bool permission = true;
  bool started = false;
  bool stopped = false;
  bool disposed = false;
  RecordConfigSnapshot? config;

  @override
  Future<bool> hasPermission() async => permission;

  @override
  Future<Stream<Uint8List>> startStream(RecordConfigSnapshot config) async {
    started = true;
    this.config = config;
    return frames.stream;
  }

  @override
  Future<void> stop() async {
    stopped = true;
  }

  @override
  Future<void> dispose() async {
    disposed = true;
    await frames.close();
  }
}

void main() {
  test('forwards immutable PCM frames with their duration', () async {
    final source = _FakeRecorderSource();
    final received = <AudioFrame>[];
    final capture = AudioCaptureController(source: source);

    expect(await capture.start(onAudioFrame: received.add), isTrue);
    expect(source.config?.sampleRate, 16000);
    expect(source.config?.channels, 1);
    expect(source.config?.pcm16, isTrue);

    final original = Uint8List.fromList(List<int>.filled(320, 7));
    source.frames.add(original);
    await Future<void>.delayed(Duration.zero);
    original[0] = 2;
    await Future<void>.delayed(Duration.zero);

    expect(received, hasLength(1));
    expect(received.single.bytes.first, 7);
    expect(received.single.durationMs, 10);

    await capture.stop();
    expect(source.stopped, isTrue);
    await capture.dispose();
    expect(source.disposed, isTrue);
  });

  test('does not start when microphone permission is denied', () async {
    final source = _FakeRecorderSource()..permission = false;
    final capture = AudioCaptureController(source: source);

    expect(await capture.start(), isFalse);
    expect(source.started, isFalse);
    expect(capture.isRunning, isFalse);
    await capture.dispose();
  });

  test('stops the recorder when the capture stream reports an error', () async {
    final source = _FakeRecorderSource();
    final capture = AudioCaptureController(source: source);
    expect(await capture.start(), isTrue);

    source.frames.addError(StateError('audio stream failed'));
    await Future<void>.delayed(Duration.zero);

    expect(capture.isRunning, isFalse);
    expect(source.stopped, isTrue);
    await capture.dispose();
  });
}
