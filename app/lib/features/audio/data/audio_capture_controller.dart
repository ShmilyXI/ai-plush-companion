import 'dart:async';
import 'dart:typed_data';

import 'package:record/record.dart';

import '../application/voice_activity_segmenter.dart';

typedef PcmFrameHandler = void Function(Uint8List frame);

class AudioCaptureController {
  AudioCaptureController({
    AudioRecorder? recorder,
    VoiceActivitySegmenter? segmenter,
  }) : _recorder = recorder ?? AudioRecorder(),
       segmenter = segmenter ?? VoiceActivitySegmenter();
  final AudioRecorder _recorder;
  final VoiceActivitySegmenter segmenter;
  StreamSubscription<Uint8List>? _subscription;
  PcmFrameHandler? onFrame;
  bool _running = false;
  bool get isRunning => _running;

  Future<bool> start({PcmFrameHandler? onFrame}) async {
    if (_running) return true;
    if (!await _recorder.hasPermission()) return false;
    this.onFrame = onFrame;
    final stream = await _recorder.startStream(
      const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: 16000,
        numChannels: 1,
      ),
    );
    _subscription = stream.listen((frame) {
      final copy = Uint8List.fromList(frame);
      this.onFrame?.call(copy);
    });
    _running = true;
    return true;
  }

  Future<void> stop() async {
    await _subscription?.cancel();
    _subscription = null;
    if (_running) await _recorder.stop();
    _running = false;
    onFrame = null;
  }

  Future<void> dispose() async {
    await stop();
    await _recorder.dispose();
  }
}
