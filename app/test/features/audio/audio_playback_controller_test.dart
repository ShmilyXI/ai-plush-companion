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
}
