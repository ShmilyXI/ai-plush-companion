import 'dart:async';

import 'package:audio_service/audio_service.dart' as audio;

import '../domain/playback_state.dart' as local;
import 'audio_playback_controller.dart';

/// Bridges the single reply queue to Android media notifications, iOS Control
/// Center and lock-screen controls. Message text is intentionally absent from
/// all system metadata.
class CompanionAudioHandler extends audio.BaseAudioHandler {
  CompanionAudioHandler({AudioPlaybackController? playback})
    : playback = playback ?? AudioPlaybackController() {
    _statusListener = _publishState;
    this.playback.addStatusListener(_statusListener);
    _publishState(this.playback.status);
  }

  final AudioPlaybackController playback;
  final _stopRequests = StreamController<void>.broadcast();
  late final PlaybackStatusListener _statusListener;
  bool _resumeAfterInterruption = false;
  bool _disposed = false;

  Stream<void> get stopRequests => _stopRequests.stream;

  @override
  Future<void> play() async {
    await playback.resume();
    _publishState(playback.status);
  }

  @override
  Future<void> pause() async {
    await playback.pause();
    _publishState(playback.status);
  }

  @override
  Future<void> stop() async {
    await playback.stop();
    _publishState(playback.status);
    if (!_disposed) _stopRequests.add(null);
  }

  /// Called by the app's AudioSession bridge when another app takes focus.
  Future<void> pauseForInterruption() async {
    _resumeAfterInterruption = playback.status == local.PlaybackStatus.playing;
    if (_resumeAfterInterruption) await pause();
  }

  Future<void> resumeAfterInterruption() async {
    if (!_resumeAfterInterruption) return;
    _resumeAfterInterruption = false;
    await play();
  }

  @override
  Future<void> onTaskRemoved() async {
    await stop();
    await super.onTaskRemoved();
  }

  @override
  Future<void> onNotificationDeleted() async {
    await stop();
  }

  Future<void> disposeHandler() async {
    if (_disposed) return;
    _disposed = true;
    playback.removeStatusListener(_statusListener);
    await playback.dispose();
    await _stopRequests.close();
  }

  @override
  Future<dynamic> customAction(
    String name, [
    Map<String, dynamic>? extras,
  ]) async {
    switch (name) {
      case 'pause':
        await pause();
        return null;
      case 'resume':
        await play();
        return null;
      case 'stop':
        await stop();
        return null;
      case 'returnToConversation':
        // The UI owns routing; this event lets it observe the notification
        // action without coupling the background isolate to GoRouter.
        customEvent.add(const {'type': 'returnToConversation'});
        return null;
      default:
        return super.customAction(name, extras);
    }
  }

  void _publishState(local.PlaybackStatus status) {
    if (_disposed) return;
    final current = playback.current;
    if (current == null || status == local.PlaybackStatus.idle) {
      mediaItem.add(null);
    } else {
      mediaItem.add(_mediaItem(current));
    }
    queue.add(
      playback.queue.map<audio.MediaItem>(_mediaItem).toList(growable: false),
    );

    final processingState = switch (status) {
      local.PlaybackStatus.idle => audio.AudioProcessingState.idle,
      local.PlaybackStatus.loading => audio.AudioProcessingState.loading,
      local.PlaybackStatus.playing => audio.AudioProcessingState.ready,
      local.PlaybackStatus.paused => audio.AudioProcessingState.ready,
      local.PlaybackStatus.completed => audio.AudioProcessingState.completed,
      local.PlaybackStatus.failed => audio.AudioProcessingState.error,
    };
    final controls = switch (status) {
      local.PlaybackStatus.playing => <audio.MediaControl>[
        audio.MediaControl.pause,
        audio.MediaControl.stop,
      ],
      local.PlaybackStatus.paused => <audio.MediaControl>[
        audio.MediaControl.play,
        audio.MediaControl.stop,
      ],
      local.PlaybackStatus.loading => <audio.MediaControl>[
        audio.MediaControl.stop,
      ],
      _ => <audio.MediaControl>[audio.MediaControl.play],
    };
    playbackState.add(
      audio.PlaybackState(
        controls: controls,
        androidCompactActionIndices: controls.length > 1
            ? const [0, 1]
            : const [0],
        processingState: processingState,
        playing: status == local.PlaybackStatus.playing,
        updatePosition: Duration(milliseconds: playback.playedMilliseconds),
        speed: 1.0,
        errorCode: status == local.PlaybackStatus.failed ? 1 : null,
        errorMessage: status == local.PlaybackStatus.failed ? '语音播放失败' : null,
        queueIndex: current == null
            ? null
            : playback.queue.indexWhere((item) => item.id == current.id),
      ),
    );
  }

  audio.MediaItem _mediaItem(local.PlaybackItem item) => audio.MediaItem(
    id: item.id,
    title: 'AI 回复',
    artist: '拾光陪伴',
    playable: true,
  );
}
