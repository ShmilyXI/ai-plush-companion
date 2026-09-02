import 'dart:async';
import 'dart:typed_data';

import 'package:record/record.dart';

import '../application/voice_activity_segmenter.dart';
import '../domain/audio_models.dart';

typedef PcmFrameHandler = void Function(Uint8List frame);
typedef AudioFrameHandler = void Function(AudioFrame frame);
typedef AudioCaptureErrorHandler =
    void Function(Object error, StackTrace stack);

class RecordConfigSnapshot {
  const RecordConfigSnapshot({
    required this.sampleRate,
    required this.channels,
    required this.pcm16,
  });

  final int sampleRate;
  final int channels;
  final bool pcm16;
}

abstract interface class AudioRecorderSource {
  Future<bool> hasPermission();
  Future<Stream<Uint8List>> startStream(RecordConfigSnapshot config);
  Future<void> stop();
  Future<void> dispose();
}

abstract interface class AudioCapture {
  bool get isRunning;
  Future<bool> start({
    PcmFrameHandler? onFrame,
    AudioFrameHandler? onAudioFrame,
  });
  Future<void> stop();
  Future<void> dispose();
}

class AudioCaptureController implements AudioCapture {
  AudioCaptureController({
    AudioRecorder? recorder,
    AudioRecorderSource? source,
    VoiceActivitySegmenter? segmenter,
  }) : _source =
           source ?? _RecordAudioRecorderSource(recorder ?? AudioRecorder()),
       segmenter = segmenter ?? VoiceActivitySegmenter();
  final AudioRecorderSource _source;
  final VoiceActivitySegmenter segmenter;
  StreamSubscription<Uint8List>? _subscription;
  PcmFrameHandler? onFrame;
  AudioFrameHandler? onAudioFrame;
  AudioCaptureErrorHandler? onError;
  bool _running = false;
  @override
  bool get isRunning => _running;

  @override
  Future<bool> start({
    PcmFrameHandler? onFrame,
    AudioFrameHandler? onAudioFrame,
  }) async {
    if (_running) return true;
    if (!await _source.hasPermission()) return false;
    this.onFrame = onFrame;
    this.onAudioFrame = onAudioFrame;
    segmenter.reset();
    late final Stream<Uint8List> stream;
    try {
      stream = await _source.startStream(
        const RecordConfigSnapshot(sampleRate: 16000, channels: 1, pcm16: true),
      );
    } catch (_) {
      this.onFrame = null;
      this.onAudioFrame = null;
      unawaited(_stopSourceQuietly());
      rethrow;
    }
    _running = true;
    _subscription = stream.listen(
      (frame) {
        final copy = Uint8List.fromList(frame);
        this.onFrame?.call(copy);
        if (copy.isNotEmpty) {
          this.onAudioFrame?.call(
            AudioFrame(copy, durationMs: _durationFor(copy.length)),
          );
        }
      },
      onError: (Object error, StackTrace stack) {
        _running = false;
        final subscription = _subscription;
        _subscription = null;
        this.onFrame = null;
        this.onAudioFrame = null;
        try {
          onError?.call(error, stack);
        } catch (_) {
          // Observer failures must not leave the recorder running.
        }
        unawaited(subscription?.cancel());
        unawaited(_stopSourceQuietly());
      },
    );
    return true;
  }

  @override
  Future<void> stop() async {
    try {
      await _subscription?.cancel();
      if (_running) await _source.stop();
    } finally {
      _subscription = null;
      _running = false;
      onFrame = null;
      onAudioFrame = null;
    }
  }

  @override
  Future<void> dispose() async {
    await stop();
    await _source.dispose();
  }

  int _durationFor(int byteLength) {
    // 16-bit mono PCM at 16 kHz contains 32 bytes per millisecond.
    return (byteLength / 32).round();
  }

  Future<void> _stopSourceQuietly() async {
    try {
      await _source.stop();
    } catch (_) {
      // The platform recorder may already have stopped after the stream error.
    }
  }
}

class _RecordAudioRecorderSource implements AudioRecorderSource {
  _RecordAudioRecorderSource(this.recorder);
  final AudioRecorder recorder;

  @override
  Future<bool> hasPermission() => recorder.hasPermission();

  @override
  Future<Stream<Uint8List>> startStream(RecordConfigSnapshot config) {
    return recorder.startStream(
      RecordConfig(
        encoder: config.pcm16 ? AudioEncoder.pcm16bits : AudioEncoder.wav,
        sampleRate: config.sampleRate,
        numChannels: config.channels,
      ),
    );
  }

  @override
  Future<void> stop() => recorder.stop();

  @override
  Future<void> dispose() => recorder.dispose();
}
