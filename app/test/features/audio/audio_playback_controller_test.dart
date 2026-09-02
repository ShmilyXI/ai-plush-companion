import 'dart:async';

import 'package:ai_plush_companion/features/audio/data/audio_playback_controller.dart';
import 'package:ai_plush_companion/features/audio/domain/playback_state.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeOutput implements AudioOutput {
  int attempts = 0;
  @override
  Future<void> play(PlaybackItem item) async {
    attempts++;
    if (attempts == 1) throw StateError('temporary');
  }

  @override
  Future<void> pause() async {}
  @override
  Future<void> resume() async {}
  @override
  Future<void> stop() async {}
}

void main() {
  test('a failed item does not block the next queued item', () async {
    final output = _FakeOutput();
    final player = AudioPlaybackController(output: output);
    await player.enqueue(
      const PlaybackItem(id: 'first', bytes: [1], mimeType: 'audio/wav'),
    );
    // enqueue stops the previous item, so a direct completion then second item
    // verifies the failure path without relying on a real player.
    await player.completeCurrent();
    await player.enqueue(
      const PlaybackItem(id: 'second', bytes: [2], mimeType: 'audio/wav'),
    );
    expect(output.attempts, 2);
    expect(player.current?.id, 'second');
    expect(player.status, PlaybackStatus.playing);
  });

  test(
    'appending a TTS chunk keeps the current response queue intact',
    () async {
      final output = _RecordingOutput();
      final player = AudioPlaybackController(output: output);
      await player.enqueue(
        const PlaybackItem(id: 'chunk-1', bytes: [1], mimeType: 'audio/wav'),
      );
      await player.append(
        const PlaybackItem(id: 'chunk-2', bytes: [2], mimeType: 'audio/wav'),
      );

      expect(player.queue.map((item) => item.id), ['chunk-1', 'chunk-2']);
      await player.completeCurrent();
      expect(output.played, ['chunk-1', 'chunk-2']);
    },
  );

  test('reports a failed output without throwing from enqueue', () async {
    final player = AudioPlaybackController(output: _FailingStopOutput());

    await player.enqueue(
      const PlaybackItem(id: 'item', bytes: [1], mimeType: 'audio/wav'),
    );

    expect(player.status, PlaybackStatus.failed);
    expect(player.current, isNull);
  });

  test('keeps cleanup non-throwing when stopping the output fails', () async {
    final player = AudioPlaybackController(output: _FailingStopOutput());

    await player.stop();

    expect(player.status, PlaybackStatus.failed);
    expect(player.queue, isEmpty);
  });

  test('advances native output queues after playback completes', () async {
    final output = _CompletingOutput();
    final player = AudioPlaybackController(output: output);

    await player.enqueue(
      const PlaybackItem(id: 'first', bytes: [1], mimeType: 'audio/wav'),
    );
    await player.append(
      const PlaybackItem(id: 'second', bytes: [2], mimeType: 'audio/wav'),
    );
    expect(output.played, ['first']);

    output.complete();
    await Future<void>.delayed(Duration.zero);
    expect(output.played, ['first', 'second']);
    expect(player.current?.id, 'second');
  });
}

class _RecordingOutput implements AudioOutput {
  final played = <String>[];

  @override
  Future<void> play(PlaybackItem item) async => played.add(item.id);

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async {}
}

class _FailingStopOutput implements AudioOutput {
  @override
  Future<void> play(PlaybackItem item) async {}

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async => throw StateError('output unavailable');
}

class _CompletingOutput implements AudioOutput, CompletionAwareAudioOutput {
  final played = <String>[];
  Completer<void>? _completion;

  @override
  Future<void> play(PlaybackItem item) async {
    played.add(item.id);
    _completion = Completer<void>();
  }

  @override
  Future<void> waitForCompletion() => _completion!.future;

  void complete() => _completion?.complete();

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async {
    final completion = _completion;
    if (completion != null && !completion.isCompleted) completion.complete();
  }
}
