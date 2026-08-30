import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../audio/application/voice_activity_segmenter.dart';
import '../../audio/domain/audio_models.dart';
import '../../chat/data/conversation_realtime_client.dart';
import '../domain/call_models.dart';

class CallController extends ChangeNotifier {
  CallController({
    ConversationRealtimeClient? client,
    VoiceActivitySegmenter? segmenter,
  }) : _client = client,
       segmenter = segmenter ?? VoiceActivitySegmenter();
  ConversationRealtimeClient? _client;
  final VoiceActivitySegmenter segmenter;
  Timer? _clock;
  DateTime? _startedAt;
  CallState state = CallState.idle;
  String transcript = '';
  String? error;
  bool muted = false;
  Duration get elapsed => _startedAt == null
      ? Duration.zero
      : DateTime.now().difference(_startedAt!);

  Future<void> start({
    required Uri streamUrl,
    required String runtimeToken,
    required String conversationId,
  }) async {
    if (state != CallState.idle &&
        state != CallState.ended &&
        state != CallState.failed) {
      return;
    }
    state = CallState.preparing;
    error = null;
    notifyListeners();
    try {
      _client ??= ConversationRealtimeClient(
        streamUrl: streamUrl,
        runtimeToken: runtimeToken,
      );
      state = CallState.connecting;
      notifyListeners();
      await _client!.connect(
        conversationId: conversationId,
        format: const {
          'format': 'pcm_s16le',
          'sample_rate': 16000,
          'channels': 1,
        },
      );
      state = CallState.listening;
      _startedAt = DateTime.now();
      _clock = Timer.periodic(
        const Duration(seconds: 1),
        (_) => notifyListeners(),
      );
    } catch (value) {
      state = CallState.failed;
      error = value.toString();
    }
    notifyListeners();
  }

  void pushPcm(AudioFrame frame) {
    if (state == CallState.muted ||
        state == CallState.ended ||
        state == CallState.failed) {
      return;
    }
    final signal = segmenter.push(
      frame.bytes,
      durationMs: frame.durationMs,
      playbackActive: state == CallState.speaking,
    );
    if (signal == VoiceActivitySignal.interrupted) {
      _client?.cancel('', 0);
      state = CallState.listening;
    } else if (signal == VoiceActivitySignal.committed) {
      _client?.commitAudio(
        DateTime.now().microsecondsSinceEpoch.toString(),
        segmenter.lastCommittedDurationMs,
      );
      state = CallState.thinking;
    }
    notifyListeners();
  }

  void setTranscript(String value) {
    transcript = value;
    notifyListeners();
  }

  void toggleMute() {
    muted = !muted;
    state = muted ? CallState.muted : CallState.listening;
    notifyListeners();
  }

  Future<void> end() async {
    if (state == CallState.ended) return;
    state = CallState.ending;
    notifyListeners();
    _client?.stop();
    await _client?.close();
    _clock?.cancel();
    _clock = null;
    state = CallState.ended;
    notifyListeners();
  }

  @override
  void dispose() {
    _clock?.cancel();
    unawaited(_client?.close());
    super.dispose();
  }
}
