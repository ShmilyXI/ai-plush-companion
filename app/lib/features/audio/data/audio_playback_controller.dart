import 'dart:async';

import 'package:just_audio/just_audio.dart';

import '../domain/playback_state.dart';

typedef PlaybackStatusListener = void Function(PlaybackStatus status);

abstract interface class AudioOutput {
  Future<void> play(PlaybackItem item);
  Future<void> pause();
  Future<void> resume();
  Future<void> stop();
}

abstract interface class CompletionAwareAudioOutput {
  Future<void> waitForCompletion();
}

abstract interface class CallPlayback {
  int get playedMilliseconds;
  PlaybackStatus get status;
  Future<void> enqueue(PlaybackItem item);
  Future<void> stop();
}

class AudioPlaybackController implements CallPlayback {
  AudioPlaybackController({AudioOutput? output})
    : _output = output ?? JustAudioOutput();
  final AudioOutput _output;
  final _queue = <PlaybackItem>[];
  PlaybackStatus _status = PlaybackStatus.idle;
  PlaybackItem? current;
  int _playedMilliseconds = 0;
  bool _busy = false;
  int _generation = 0;
  final _statusListeners = <PlaybackStatusListener>[];

  @override
  PlaybackStatus get status => _status;

  set status(PlaybackStatus value) {
    if (_status == value) return;
    _status = value;
    _notifyStatusListeners();
  }

  void addStatusListener(PlaybackStatusListener listener) {
    if (!_statusListeners.contains(listener)) _statusListeners.add(listener);
  }

  void removeStatusListener(PlaybackStatusListener listener) {
    _statusListeners.remove(listener);
  }

  List<PlaybackItem> get queue => List.unmodifiable(_queue);

  @override
  int get playedMilliseconds {
    final output = _output;
    if ((status == PlaybackStatus.playing || status == PlaybackStatus.paused) &&
        output is JustAudioOutput) {
      return output.position.inMilliseconds;
    }
    return _playedMilliseconds;
  }

  @override
  Future<void> enqueue(PlaybackItem item) async {
    try {
      await stop();
    } catch (_) {
      status = PlaybackStatus.failed;
      current = null;
      _queue.clear();
      return;
    }
    if (status == PlaybackStatus.failed) return;
    _queue.add(item);
    await _playNext(_generation);
  }

  /// Adds another sentence/chunk to the current response without interrupting
  /// the item already playing. A new response should use [enqueue] instead.
  Future<void> append(PlaybackItem item) async {
    final shouldStart = current == null && !_busy;
    _queue.add(item);
    _notifyStatusListeners();
    if (shouldStart) await _playNext(_generation);
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

  @override
  Future<void> stop() async {
    _generation++;
    _queue.clear();
    current = null;
    _playedMilliseconds = 0;
    _notifyStatusListeners();
    try {
      await _output.stop();
      status = PlaybackStatus.idle;
    } catch (_) {
      status = PlaybackStatus.failed;
    }
  }

  Future<void> completeCurrent() async {
    if (current == null) return;
    _queue.remove(current);
    current = null;
    status = PlaybackStatus.completed;
    await _playNext(_generation);
  }

  Future<void> dispose() async {
    await stop();
    _statusListeners.clear();
    final output = _output;
    if (output is JustAudioOutput) {
      await output.dispose();
    }
  }

  Future<void> _playNext(int generation) async {
    if (_busy || _queue.isEmpty || generation != _generation) return;
    _busy = true;
    try {
      while (_queue.isNotEmpty && generation == _generation) {
        current = _queue.first;
        _notifyStatusListeners();
        status = PlaybackStatus.loading;
        try {
          await _output.play(current!);
          if (generation != _generation) return;
          status = PlaybackStatus.playing;
          final output = _output;
          if (output is CompletionAwareAudioOutput) {
            unawaited(
              _watchCompletion(
                output as CompletionAwareAudioOutput,
                current!,
                generation,
              ),
            );
          }
          return;
        } catch (_) {
          if (generation != _generation) return;
          _queue.remove(current);
          current = null;
          status = PlaybackStatus.failed;
        }
      }
    } finally {
      _busy = false;
    }
  }

  Future<void> _watchCompletion(
    CompletionAwareAudioOutput output,
    PlaybackItem playingItem,
    int generation,
  ) async {
    try {
      await output.waitForCompletion();
    } catch (_) {
      if (generation == _generation && current == playingItem) {
        _queue.remove(playingItem);
        current = null;
        status = PlaybackStatus.failed;
      }
      return;
    }
    if (generation != _generation || current != playingItem) return;
    _queue.remove(playingItem);
    current = null;
    status = PlaybackStatus.completed;
    await _playNext(generation);
  }

  void _notifyStatusListeners() {
    if (_statusListeners.isEmpty) return;
    for (final listener in List<PlaybackStatusListener>.of(_statusListeners)) {
      try {
        listener(_status);
      } catch (_) {
        // A media notification observer must not break the playback queue.
      }
    }
  }
}

/// Uses a short-lived data URI so response bytes never become app files.
class JustAudioOutput implements AudioOutput, CompletionAwareAudioOutput {
  JustAudioOutput({AudioPlayer? player}) : _player = player ?? AudioPlayer();
  final AudioPlayer _player;

  Duration get position => _player.position;

  @override
  Future<void> play(PlaybackItem item) async {
    await _player.setAudioSource(
      AudioSource.uri(Uri.dataFromBytes(item.bytes, mimeType: item.mimeType)),
    );
    await _player.play();
  }

  @override
  Future<void> pause() => _player.pause();

  @override
  Future<void> resume() => _player.play();

  @override
  Future<void> stop() => _player.stop();

  @override
  Future<void> waitForCompletion() async {
    await _player.processingStateStream.firstWhere(
      (state) =>
          state == ProcessingState.completed || state == ProcessingState.idle,
    );
  }

  Future<void> dispose() => _player.dispose();
}
