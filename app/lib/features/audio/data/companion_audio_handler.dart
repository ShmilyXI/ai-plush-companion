import 'package:audio_service/audio_service.dart';

import 'audio_playback_controller.dart';

/// Bridges system media controls to the single reply queue. Message contents
/// are deliberately absent from the notification metadata.
class CompanionAudioHandler extends BaseAudioHandler {
  CompanionAudioHandler({AudioPlaybackController? playback})
    : playback = playback ?? AudioPlaybackController();

  final AudioPlaybackController playback;

  @override
  Future<void> play() => playback.resume();

  @override
  Future<void> pause() => playback.pause();

  @override
  Future<void> stop() => playback.stop();

  @override
  Future<dynamic> customAction(String name, [Map<String, dynamic>? extras]) {
    switch (name) {
      case 'pause':
        return playback.pause();
      case 'resume':
        return playback.resume();
      case 'stop':
        return playback.stop();
      case 'returnToConversation':
        return Future<void>.value();
      default:
        return super.customAction(name, extras);
    }
  }
}
