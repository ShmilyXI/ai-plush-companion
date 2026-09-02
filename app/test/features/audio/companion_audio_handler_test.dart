import 'package:ai_plush_companion/features/audio/data/audio_playback_controller.dart';
import 'package:ai_plush_companion/features/audio/data/companion_audio_handler.dart';
import 'package:ai_plush_companion/features/audio/data/companion_audio_service.dart';
import 'package:ai_plush_companion/features/audio/domain/playback_state.dart';
import 'package:flutter_test/flutter_test.dart';

class _Output implements AudioOutput {
  int playCalls = 0;
  int pauseCalls = 0;
  int resumeCalls = 0;
  int stopCalls = 0;

  @override
  Future<void> play(PlaybackItem item) async => playCalls++;

  @override
  Future<void> pause() async => pauseCalls++;

  @override
  Future<void> resume() async => resumeCalls++;

  @override
  Future<void> stop() async => stopCalls++;
}

void main() {
  test(
    'publishes generic lock-screen metadata and mirrors media actions',
    () async {
      final output = _Output();
      final playback = AudioPlaybackController(output: output);
      final handler = CompanionAudioHandler(playback: playback);

      await playback.enqueue(
        const PlaybackItem(
          id: 'opaque-audio-id',
          bytes: [1, 2],
          mimeType: 'audio/wav',
        ),
      );
      expect(handler.mediaItem.value?.id, 'opaque-audio-id');
      expect(handler.mediaItem.value?.title, 'AI 回复');
      expect(handler.playbackState.value.playing, isTrue);

      await handler.pause();
      expect(output.pauseCalls, 1);
      expect(handler.playbackState.value.playing, isFalse);

      await handler.play();
      expect(output.resumeCalls, 1);
      expect(handler.playbackState.value.playing, isTrue);

      await handler.customAction('stop');
      expect(output.stopCalls, greaterThanOrEqualTo(1));
      expect(handler.playbackState.value.playing, isFalse);
    },
  );

  test(
    'task removal stops playback and does not expose message text',
    () async {
      final output = _Output();
      final playback = AudioPlaybackController(output: output);
      final handler = CompanionAudioHandler(playback: playback);
      await playback.enqueue(
        const PlaybackItem(id: 'opaque-id', bytes: [1], mimeType: 'audio/wav'),
      );

      await handler.onTaskRemoved();

      expect(output.stopCalls, greaterThanOrEqualTo(1));
      expect(handler.mediaItem.value?.title, isNot(contains('opaque')));
    },
  );

  test('initializes the system audio handler once and reuses it', () async {
    final playback = AudioPlaybackController(output: _Output());
    final handler = CompanionAudioHandler(playback: playback);
    var factoryCalls = 0;
    var configureCalls = 0;
    final service = CompanionAudioService(
      handlerInitializer: () async {
        factoryCalls++;
        return handler;
      },
      sessionConfigurator: () async => configureCalls++,
    );

    final first = await service.initialize();
    final second = await service.initialize();

    expect(identical(first, second), isTrue);
    expect(factoryCalls, 1);
    expect(configureCalls, 1);
    await service.dispose();
  });
}
