import 'dart:async';
import 'dart:typed_data';

import '../../chat/data/conversation_realtime_client.dart';
import '../domain/audio_models.dart';
import 'audio_capture_controller.dart';

abstract interface class AudioTurnTransport {
  bool get isConnected;

  void startAudio(
    String requestId, {
    required int durationMs,
    required String mimeType,
  });

  void pushAudio(Uint8List bytes);

  void endAudio(String requestId);
}

/// Adapter used by the App chat flow. The public WebSocket accepts the
/// duration as optional metadata; older client implementations omit it while
/// still enforcing the same 60-second limit locally.
class ConversationAudioTurnTransport implements AudioTurnTransport {
  ConversationAudioTurnTransport(this.client);
  final ConversationRealtimeClient client;

  @override
  bool get isConnected => client.isConnected;

  @override
  void startAudio(
    String requestId, {
    required int durationMs,
    required String mimeType,
  }) {
    client.startAudio(requestId, mimeType: mimeType, durationMs: durationMs);
  }

  @override
  void pushAudio(Uint8List bytes) => client.pushAudio(bytes);

  @override
  void endAudio(String requestId) => client.endAudio(requestId);
}

class AudioTurnResult {
  const AudioTurnResult({
    required this.requestId,
    required this.durationMs,
    required this.byteLength,
    required this.mimeType,
  });

  final String requestId;
  final int durationMs;
  final int byteLength;
  final String mimeType;
}

abstract interface class VoiceMessageSender {
  bool get isRecording;
  Future<bool> start();
  Future<AudioTurnResult?> stop({bool cancelled = false});
}

/// Buffers a press-to-send recording and emits one protocol audio turn when
/// the user releases the gesture. It deliberately owns no widget state.
class AudioTurnSender implements VoiceMessageSender {
  AudioTurnSender({
    required AudioCapture capture,
    required AudioTurnTransport transport,
    String Function()? requestId,
    this.mimeType = 'audio/pcm',
    this.maxDurationMs = 60 * 1000,
  }) : _capture = capture,
       _transport = transport,
       _requestId = requestId ?? _defaultRequestId;

  final AudioCapture _capture;
  final AudioTurnTransport _transport;
  final String Function() _requestId;
  final String mimeType;
  final int maxDurationMs;
  final List<Uint8List> _frames = <Uint8List>[];
  bool _recording = false;
  Future<bool>? _startFuture;
  int _durationMs = 0;
  int _byteLength = 0;
  String? lastError;

  @override
  bool get isRecording => _recording;
  int get durationMs => _durationMs;
  int get byteLength => _byteLength;

  @override
  Future<bool> start() {
    final pending = _startFuture;
    if (pending != null) return pending;
    if (_recording) return Future<bool>.value(true);
    final completer = Completer<bool>();
    _startFuture = completer.future;
    _startInternal().then(
      (value) {
        if (!completer.isCompleted) completer.complete(value);
        if (identical(_startFuture, completer.future)) _startFuture = null;
      },
      onError: (Object error, StackTrace stack) {
        if (!completer.isCompleted) completer.completeError(error, stack);
        if (identical(_startFuture, completer.future)) _startFuture = null;
      },
    );
    return completer.future;
  }

  Future<bool> _startInternal() async {
    _recording = true;
    _frames.clear();
    _durationMs = 0;
    _byteLength = 0;
    lastError = null;
    try {
      final started = await _capture.start(onAudioFrame: _onFrame);
      if (!started) {
        _recording = false;
        lastError = '麦克风权限未授予';
        return false;
      }
      return true;
    } catch (_) {
      _recording = false;
      _discard();
      rethrow;
    }
  }

  @override
  Future<AudioTurnResult?> stop({bool cancelled = false}) async {
    final pending = _startFuture;
    if (pending != null) {
      try {
        await pending;
      } catch (_) {
        return null;
      }
    }
    if (!_recording) return null;
    _recording = false;
    try {
      await _capture.stop();
    } catch (error) {
      lastError = error.toString();
      _discard();
      rethrow;
    }
    if (cancelled || _frames.isEmpty) {
      _discard();
      return null;
    }
    if (!_transport.isConnected) {
      lastError = '实时连接已断开';
      _discard();
      return null;
    }
    final id = _requestId();
    try {
      _transport.startAudio(id, durationMs: _durationMs, mimeType: mimeType);
      for (final frame in _frames) {
        _transport.pushAudio(frame);
      }
      _transport.endAudio(id);
      return AudioTurnResult(
        requestId: id,
        durationMs: _durationMs,
        byteLength: _byteLength,
        mimeType: mimeType,
      );
    } catch (error) {
      lastError = error.toString();
      return null;
    } finally {
      _discard();
    }
  }

  Future<AudioTurnResult?> cancel() => stop(cancelled: true);

  Future<void> dispose() async {
    if (_recording) await stop(cancelled: true);
    await _capture.dispose();
  }

  void _onFrame(AudioFrame frame) {
    if (!_recording || !_capture.isRunning) return;
    if (frame.bytes.isEmpty || _durationMs >= maxDurationMs) return;
    final remaining = maxDurationMs - _durationMs;
    final acceptedDuration = frame.durationMs.clamp(0, remaining);
    if (acceptedDuration <= 0) return;
    final acceptedBytes =
        frame.durationMs <= 0 || acceptedDuration == frame.durationMs
        ? frame.bytes
        : _truncatePcm(frame.bytes, frame.durationMs, acceptedDuration);
    if (acceptedBytes.isEmpty) return;
    final copy = Uint8List.fromList(acceptedBytes);
    _frames.add(copy);
    _durationMs += acceptedDuration;
    _byteLength += copy.length;
  }

  void _discard() {
    _frames.clear();
    _durationMs = 0;
    _byteLength = 0;
  }

  Uint8List _truncatePcm(Uint8List bytes, int frameDuration, int duration) {
    var length = (bytes.length * duration / frameDuration).floor();
    length = length.clamp(0, bytes.length);
    length -= length % 2;
    return Uint8List.fromList(bytes.sublist(0, length));
  }

  static String _defaultRequestId() =>
      DateTime.now().microsecondsSinceEpoch.toString();
}
