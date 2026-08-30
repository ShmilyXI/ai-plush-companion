import 'dart:typed_data';

enum VoiceActivitySignal {
  none,
  speechStarted,
  speechContinued,
  committed,
  interrupted,
}

class AudioFrame {
  const AudioFrame(this.bytes, {required this.durationMs});
  final Uint8List bytes;
  final int durationMs;
}
