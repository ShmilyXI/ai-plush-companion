import 'dart:async';

import '../domain/playback_state.dart';

abstract interface class AudioOutput {
  Future<void> play(PlaybackItem item);
  Future<void> pause();
  Future<void> resume();
  Future<void> stop();
}

class AudioPlaybackController {
  AudioPlaybackController({AudioOutput? output})
    : _output = output ?? _SilentAudioOutput();
  final AudioOutput _output;
  final _queue = <PlaybackItem>[];
  PlaybackStatus status = PlaybackStatus.idle;
  PlaybackItem? current;
  int playedMilliseconds = 0;
  bool _busy = false;

  List<PlaybackItem> get queue => List.unmodifiable(_queue);

  Future<void> enqueue(PlaybackItem item) async {
    await stop();
    _queue.add(item);
    await _playNext();
  }

  Future<void> pause() async {
    if (status != PlaybackStatus.playing) return;
    await _output.pause();
    status = PlaybackStatus.paused;
  }

  Future<void> resume() async {
    if (status != PlaybackStatus.paused) return;
    await _output.resume();
    status = PlaybackStatus.playing;
  }

  Future<void> stop() async {
    _queue.clear();
    current = null;
    playedMilliseconds = 0;
    await _output.stop();
    status = PlaybackStatus.idle;
  }

  Future<void> completeCurrent() async {
    if (current == null) return;
    _queue.remove(current);
    current = null;
    status = PlaybackStatus.completed;
    await _playNext();
  }

  Future<void> _playNext() async {
    if (_busy || _queue.isEmpty) return;
    _busy = true;
    try {
      while (_queue.isNotEmpty) {
        current = _queue.first;
        status = PlaybackStatus.loading;
        try {
          await _output.play(current!);
          status = PlaybackStatus.playing;
          return;
        } catch (_) {
          _queue.remove(current);
          current = null;
          status = PlaybackStatus.failed;
        }
      }
    } finally {
      _busy = false;
    }
  }
}

class _SilentAudioOutput implements AudioOutput {
  @override
  Future<void> play(PlaybackItem item) async {}
  @override
  Future<void> pause() async {}
  @override
  Future<void> resume() async {}
  @override
  Future<void> stop() async {}
}
