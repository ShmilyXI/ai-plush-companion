import 'dart:math' as math;
import 'dart:typed_data';

import '../domain/audio_models.dart';

class VoiceActivitySegmenter {
  VoiceActivitySegmenter({
    this.calibrationFrames = 8,
    this.onsetFrames = 2,
    this.hangoverMs = 700,
    this.maxSegmentMs = 30 * 1000,
    this.speechMultiplier = 2.7,
  });

  final int calibrationFrames;
  final int onsetFrames;
  final int hangoverMs;
  final int maxSegmentMs;
  final double speechMultiplier;
  double _noiseFloor = 0;
  int _calibrated = 0;
  int _speechFrames = 0;
  int _silenceMs = 0;
  int _segmentMs = 0;
  int _lastCommittedDurationMs = 0;
  bool _active = false;

  bool get isActive => _active;
  double get noiseFloor => _noiseFloor;
  int get segmentDurationMs => _segmentMs;
  int get lastCommittedDurationMs => _lastCommittedDurationMs;

  VoiceActivitySignal push(
    Uint8List frame, {
    required int durationMs,
    bool playbackActive = false,
  }) {
    final rms = _rms(frame);
    if (_calibrated < calibrationFrames) {
      _noiseFloor = _calibrated == 0
          ? rms
          : (_noiseFloor * _calibrated + rms) / (_calibrated + 1);
      _calibrated++;
      return VoiceActivitySignal.none;
    }
    final threshold = math.max(0.008, _noiseFloor * speechMultiplier + 0.002);
    final speech = rms >= threshold;
    if (speech && playbackActive && !_active) {
      return VoiceActivitySignal.interrupted;
    }
    if (speech) {
      _speechFrames++;
      _silenceMs = 0;
      if (!_active && _speechFrames >= onsetFrames) {
        _active = true;
        _segmentMs = durationMs;
        return VoiceActivitySignal.speechStarted;
      }
      if (_active) {
        _segmentMs += durationMs;
        if (_segmentMs >= maxSegmentMs) {
          _lastCommittedDurationMs = _segmentMs;
          _active = false;
          _speechFrames = 0;
          _segmentMs = 0;
          return VoiceActivitySignal.committed;
        }
        return VoiceActivitySignal.speechContinued;
      }
    } else {
      _speechFrames = 0;
      if (_active) {
        _segmentMs += durationMs;
        _silenceMs += durationMs;
        if (_silenceMs >= hangoverMs) {
          _lastCommittedDurationMs = _segmentMs;
          _active = false;
          _silenceMs = 0;
          _segmentMs = 0;
          return VoiceActivitySignal.committed;
        }
      }
    }
    return VoiceActivitySignal.none;
  }

  VoiceActivitySignal commitIfActive() {
    if (!_active) return VoiceActivitySignal.none;
    _lastCommittedDurationMs = _segmentMs;
    _active = false;
    _speechFrames = 0;
    _silenceMs = 0;
    _segmentMs = 0;
    return VoiceActivitySignal.committed;
  }

  void cancel() {
    _active = false;
    _speechFrames = 0;
    _silenceMs = 0;
    _segmentMs = 0;
  }

  void reset() {
    cancel();
    _noiseFloor = 0;
    _calibrated = 0;
    _lastCommittedDurationMs = 0;
  }

  double _rms(Uint8List bytes) {
    if (bytes.length < 2) return 0;
    var sum = 0.0;
    var count = 0;
    for (var i = 0; i + 1 < bytes.length; i += 2) {
      var sample = bytes[i] | (bytes[i + 1] << 8);
      if (sample & 0x8000 != 0) sample -= 0x10000;
      final normalized = sample / 32768.0;
      sum += normalized * normalized;
      count++;
    }
    return count == 0 ? 0 : math.sqrt(sum / count);
  }
}
