import 'dart:typed_data';

import 'package:ai_plush_companion/features/audio/application/voice_activity_segmenter.dart';
import 'package:ai_plush_companion/features/audio/domain/audio_models.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List pcm(double amplitude, [int samples = 160]) {
  final bytes = ByteData(samples * 2);
  final value = (amplitude * 32767).round();
  for (var i = 0; i < samples; i++) {
    bytes.setInt16(i * 2, value, Endian.little);
  }
  return bytes.buffer.asUint8List();
}

void main() {
  test('calibrates noise and commits after the silence hangover', () {
    final segmenter = VoiceActivitySegmenter(
      calibrationFrames: 2,
      onsetFrames: 2,
      hangoverMs: 200,
    );
    for (var i = 0; i < 2; i++) {
      segmenter.push(pcm(.001), durationMs: 20);
    }
    expect(segmenter.push(pcm(.2), durationMs: 20), VoiceActivitySignal.none);
    expect(
      segmenter.push(pcm(.2), durationMs: 20),
      VoiceActivitySignal.speechStarted,
    );
    expect(
      segmenter.push(pcm(.001), durationMs: 100),
      VoiceActivitySignal.none,
    );
    expect(
      segmenter.push(pcm(.001), durationMs: 100),
      VoiceActivitySignal.committed,
    );
  });

  test(
    'short noise does not start a segment and playback speech interrupts',
    () {
      final segmenter = VoiceActivitySegmenter(
        calibrationFrames: 1,
        onsetFrames: 2,
      );
      segmenter.push(pcm(.001), durationMs: 20);
      expect(
        segmenter.push(pcm(.02), durationMs: 20),
        VoiceActivitySignal.none,
      );
      expect(
        segmenter.push(pcm(.3), durationMs: 20, playbackActive: true),
        VoiceActivitySignal.interrupted,
      );
    },
  );
}
