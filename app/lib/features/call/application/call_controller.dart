import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/config/app_config.dart';
import '../../audio/application/voice_activity_segmenter.dart';
import '../../audio/data/audio_capture_controller.dart';
import '../../audio/data/audio_playback_controller.dart';
import '../../audio/domain/audio_models.dart';
import '../../audio/domain/playback_state.dart';
import '../../chat/data/conversation_realtime_client.dart';
import '../../chat/domain/realtime_models.dart';
import '../domain/call_models.dart';
import 'call_transport.dart';

typedef CallAudioSessionStarter = Future<bool> Function();
typedef CallAudioSessionStopper = Future<void> Function();
typedef CallClientFactory =
    ConversationRealtimeClient Function(Uri streamUrl, String runtimeToken);
typedef CallTransportFactory =
    CallTransport Function(Uri streamUrl, String runtimeToken);

class CallController extends ChangeNotifier {
  CallController({
    ConversationRealtimeClient? client,
    CallTransport? transport,
    this.runtimeWsOrigin,
    this.clientFactory,
    this.transportFactory,
    AudioCapture? capture,
    CallPlayback? playback,
    VoiceActivitySegmenter? segmenter,
    String Function()? requestId,
    CallAudioSessionStarter? configureAudioSession,
    CallAudioSessionStopper? deactivateAudioSession,
    Stream<bool>? audioInterruptions,
    Stream<void>? stopRequests,
  }) : _client = client,
       _transport =
           transport ??
           (client == null ? null : ConversationCallTransport(client)),
       capture = capture ?? AudioCaptureController(),
       playback = playback ?? AudioPlaybackController(),
       segmenter = segmenter ?? VoiceActivitySegmenter(),
       _requestId = requestId ?? _defaultRequestId,
       _ownsCapture = capture == null,
       _ownsPlayback = playback == null,
       _providedTransport = transport != null || client != null {
    _configureAudioSession = configureAudioSession;
    _deactivateAudioSession = deactivateAudioSession;
    if (audioInterruptions != null) {
      _interruptionSubscription = audioInterruptions.listen((begin) {
        if (begin) {
          unawaited(pauseForInterruption());
        } else {
          unawaited(resumeAfterInterruption());
        }
      });
    }
    if (stopRequests != null) {
      _stopRequestSubscription = stopRequests.listen((_) => unawaited(end()));
    }
    if (this.capture is AudioCaptureController) {
      (this.capture as AudioCaptureController).onError = _handleCaptureError;
    }
    _attachPlaybackStatusListener(this.playback);
  }

  final AudioCapture capture;
  final CallPlayback playback;
  final VoiceActivitySegmenter segmenter;
  final String Function() _requestId;
  final bool _ownsCapture;
  final bool _ownsPlayback;
  final bool _providedTransport;
  final Uri? runtimeWsOrigin;
  final CallClientFactory? clientFactory;
  final CallTransportFactory? transportFactory;
  CallAudioSessionStarter? _configureAudioSession;
  CallAudioSessionStopper? _deactivateAudioSession;
  StreamSubscription<bool>? _interruptionSubscription;
  StreamSubscription<void>? _stopRequestSubscription;
  PlaybackStatusListener? _playbackListener;
  ConversationRealtimeClient? _client;
  CallTransport? _transport;
  StreamSubscription<RealtimeEvent>? _events;
  Timer? _clock;
  DateTime? _startedAt;
  String? _conversationId;
  String? _activeTurnId;
  String? _playbackTurnId;
  String _assistantTranscript = '';
  final Set<String> _cancelledTurns = <String>{};
  CallState _stateBeforeMute = CallState.listening;
  bool _disposed = false;
  bool _ending = false;
  bool _startedOnce = false;
  bool _audioSessionActive = false;
  Future<void> _eventChain = Future<void>.value();
  int _generation = 0;

  CallState state = CallState.idle;
  String transcript = '';
  String? error;
  bool muted = false;

  Duration get elapsed => _startedAt == null
      ? Duration.zero
      : DateTime.now().difference(_startedAt!);
  String? get conversationId => _conversationId;
  String? get activeTurnId => _activeTurnId;
  CallTransport? get transport => _transport;
  bool get isConnected => _transport?.isConnected ?? false;
  bool get isRecording => capture.isRunning;
  CallSnapshot get snapshot => CallSnapshot(
    state: state,
    elapsed: elapsed,
    transcript: transcript,
    error: error,
  );

  /// Renews/loads the short-lived runtime issued for an existing persistent
  /// conversation before entering the full-screen call.
  Future<void> startFromRuntime({
    String? conversationId,
    required Future<Map<String, dynamic>> Function() runtime,
    String? profileId,
  }) async {
    if (_disposed ||
        (state != CallState.idle &&
            state != CallState.ended &&
            state != CallState.failed)) {
      return;
    }
    state = CallState.preparing;
    error = null;
    _notify();
    try {
      final payload = await runtime();
      final metadata = payload['data'] is Map
          ? Map<String, dynamic>.from(payload['data'] as Map)
          : payload;
      final token = _stringValue(metadata, const [
        'runtimeToken',
        'runtime_token',
      ]);
      final url = _stringValue(metadata, const ['streamUrl', 'stream_url']);
      final resolvedConversationId =
          conversationId ??
          _stringValue(metadata, const ['conversationId', 'conversation_id']);
      if (token == null ||
          token.isEmpty ||
          url == null ||
          url.isEmpty ||
          resolvedConversationId == null ||
          resolvedConversationId.isEmpty) {
        throw StateError('运行时会话响应不完整');
      }
      state = CallState.idle;
      await start(
        streamUrl: Uri.parse(url),
        runtimeToken: token,
        conversationId: resolvedConversationId,
        profileId: profileId,
      );
    } catch (value) {
      error = value.toString();
      state = CallState.failed;
      _notify();
    }
  }

  Future<void> start({
    required Uri streamUrl,
    required String runtimeToken,
    required String conversationId,
    String? profileId,
  }) async {
    if (state != CallState.idle &&
        state != CallState.ended &&
        state != CallState.failed) {
      return;
    }
    if (state == CallState.failed && _transport is ConversationCallTransport) {
      await _closeTransportQuietly();
      _transport = null;
      _client = null;
      _startedOnce = false;
    }
    ++_generation;
    _ending = false;
    error = null;
    transcript = '';
    _assistantTranscript = '';
    muted = false;
    _cancelledTurns.clear();
    _conversationId = conversationId;
    state = CallState.preparing;
    _notify();
    try {
      final resolvedStreamUrl = AppConfig.rewriteRuntimeStreamUrl(
        streamUrl,
        runtimeWsOrigin,
      );
      final configureSession = _configureAudioSession;
      if (configureSession != null) {
        if (!await configureSession()) {
          throw StateError('系统音频会话不可用');
        }
        _audioSessionActive = true;
      }
      await _resetTransportIfNeeded(resolvedStreamUrl, runtimeToken);
      final transport = _transport!;
      state = CallState.connecting;
      _notify();
      await _events?.cancel();
      _events = transport.events.listen(
        _handleEvent,
        onError: _handleTransportError,
        onDone: _handleTransportDone,
      );
      await transport.connect(
        conversationId: conversationId,
        profileId: profileId,
        format: const {
          'format': 'pcm_s16le',
          'sample_rate': 16000,
          'channels': 1,
        },
      );
      // Mark the stream active before starting the recorder so a platform
      // implementation that emits its first frame synchronously is not lost.
      state = CallState.listening;
      final started = await capture.start(onAudioFrame: pushPcm);
      if (!started) throw StateError('麦克风权限未授予');
      _startedOnce = true;
      _startedAt = DateTime.now();
      _clock?.cancel();
      _clock = Timer.periodic(const Duration(seconds: 1), (_) => _notify());
    } catch (value) {
      error = value.toString();
      await _stopCaptureQuietly();
      await _closeTransportQuietly();
      await _deactivateAudioSessionQuietly();
      state = CallState.failed;
    }
    _notify();
  }

  /// Sends every captured PCM frame while keeping the single capture stream
  /// alive. VAD commits only once per segment after the silence hangover.
  void pushPcm(AudioFrame frame) {
    if (_disposed || _ending || muted || !_isActiveState) return;
    final transport = _transport;
    if (transport == null || !transport.isConnected) return;
    try {
      if (frame.bytes.isNotEmpty) transport.pushAudio(frame.bytes);
      final signal = segmenter.push(
        frame.bytes,
        durationMs: frame.durationMs,
        playbackActive: state == CallState.speaking,
      );
      switch (signal) {
        case VoiceActivitySignal.interrupted:
          _cancelActiveResponse();
          break;
        case VoiceActivitySignal.committed:
          final duration = segmenter.lastCommittedDurationMs;
          if (duration > 0) {
            transport.commitAudio(_requestId(), duration);
            state = CallState.thinking;
          }
          break;
        case VoiceActivitySignal.none:
        case VoiceActivitySignal.speechStarted:
        case VoiceActivitySignal.speechContinued:
          break;
      }
    } catch (value) {
      error = value.toString();
      state = CallState.failed;
      unawaited(_stopCaptureQuietly());
      unawaited(_deactivateAudioSessionQuietly());
    }
    _notify();
  }

  void setTranscript(String value) {
    transcript = value;
    _notify();
  }

  void toggleMute() {
    if (!_isActiveState && state != CallState.muted) return;
    if (muted) {
      muted = false;
      state = _stateBeforeMute == CallState.muted
          ? CallState.listening
          : _stateBeforeMute;
    } else {
      _stateBeforeMute = state;
      muted = true;
      state = CallState.muted;
    }
    _notify();
  }

  Future<void> pauseForInterruption() async {
    if (!_isActiveState || state == CallState.muted) return;
    _stateBeforeMute = state;
    state = CallState.reconnecting;
    await _stopCaptureQuietly();
    _notify();
  }

  Future<void> resumeAfterInterruption() async {
    if (state != CallState.reconnecting || _ending) return;
    state = muted ? CallState.muted : CallState.listening;
    bool started;
    try {
      if (_configureAudioSession != null && !await _configureAudioSession!()) {
        throw StateError('系统音频会话不可用');
      }
      started = await capture.start(onAudioFrame: pushPcm);
    } catch (value) {
      error = value.toString();
      state = CallState.failed;
      _notify();
      return;
    }
    if (!started) {
      error = '麦克风权限未授予';
      state = CallState.failed;
    }
    _notify();
  }

  Future<void> end() async {
    if (_ending || state == CallState.ended || state == CallState.idle) return;
    _ending = true;
    ++_generation;
    state = CallState.ending;
    _notify();
    segmenter.cancel();
    await _stopCaptureQuietly();
    await _stopPlaybackQuietly();
    final transport = _transport;
    transport?.stop();
    await _events?.cancel();
    _events = null;
    try {
      await _eventChain.timeout(const Duration(milliseconds: 500));
    } catch (_) {
      // A provider callback may be waiting on a platform audio operation.
    }
    await _closeTransportQuietly();
    await _deactivateAudioSessionQuietly();
    _clock?.cancel();
    _clock = null;
    _activeTurnId = null;
    _playbackTurnId = null;
    _assistantTranscript = '';
    _cancelledTurns.clear();
    state = CallState.ended;
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    _ending = true;
    _clock?.cancel();
    final currentPlayback = playback;
    final playbackListener = _playbackListener;
    if (currentPlayback is AudioPlaybackController &&
        playbackListener != null) {
      currentPlayback.removeStatusListener(playbackListener);
    }
    _playbackListener = null;
    unawaited(_events?.cancel());
    unawaited(_interruptionSubscription?.cancel());
    unawaited(_stopRequestSubscription?.cancel());
    unawaited(_stopCaptureQuietly());
    unawaited(_closeTransportQuietly());
    if (_ownsCapture) unawaited(capture.dispose());
    if (_ownsPlayback && playback is AudioPlaybackController) {
      unawaited((playback as AudioPlaybackController).dispose());
    }
    unawaited(_deactivateAudioSessionQuietly());
    super.dispose();
  }

  Future<void> _resetTransportIfNeeded(Uri streamUrl, String token) async {
    if (transportFactory != null) {
      await _closeTransportQuietly();
      _client = null;
      _transport = transportFactory!(streamUrl, token);
      return;
    }
    if (_transport != null && _providedTransport && !_startedOnce) return;
    if (_transport != null && _transport is! ConversationCallTransport) {
      // Injected test/platform transports own their reconnect policy.
      return;
    }
    if (_transport != null && _client != null && _client!.isConnected) {
      return;
    }
    await _closeTransportQuietly();
    _client =
        clientFactory?.call(streamUrl, token) ??
        ConversationRealtimeClient(streamUrl: streamUrl, runtimeToken: token);
    _transport = ConversationCallTransport(_client!);
  }

  void _attachPlaybackStatusListener(CallPlayback value) {
    if (value is! AudioPlaybackController) return;
    void listener(PlaybackStatus status) {
      if (_disposed || _ending) return;
      if (status == PlaybackStatus.failed) {
        error = '语音播放失败';
        state = CallState.failed;
        unawaited(_stopCaptureQuietly());
        _notify();
        return;
      }
      if ((status == PlaybackStatus.completed ||
              status == PlaybackStatus.idle) &&
          value.current == null &&
          value.queue.isEmpty &&
          _playbackTurnId != null) {
        _playbackTurnId = null;
        if (_isActiveState || state == CallState.speaking) {
          state = CallState.listening;
          _notify();
        }
      }
    }

    _playbackListener = listener;
    value.addStatusListener(listener);
  }

  void _handleEvent(RealtimeEvent event) {
    final generation = _generation;
    final previous = _eventChain;
    _eventChain = previous.then<void>((_) async {
      if (!_isEventCurrent(generation)) return;
      try {
        await _processEvent(event, generation);
      } catch (value) {
        if (_isEventCurrent(generation)) {
          error = value.toString();
          state = CallState.failed;
          _notify();
        }
      }
    });
  }

  bool _isEventCurrent(int generation) =>
      !_disposed && !_ending && _generation == generation;

  Future<void> _processEvent(RealtimeEvent event, int generation) async {
    if (!_isEventCurrent(generation)) return;
    switch (event.type) {
      case 'turn.started':
        _activeTurnId =
            event.turnId ??
            event.requestId ??
            event.details['request_id']?.toString();
        _assistantTranscript = '';
        if (_activeTurnId != null) _cancelledTurns.remove(_activeTurnId);
        state = CallState.thinking;
        break;
      case 'asr.partial':
      case 'asr.final':
        final text = _eventText(event);
        if (text.isNotEmpty) transcript = text;
        break;
      case 'llm.delta':
        final delta = _eventText(event);
        if (delta.isNotEmpty) {
          _assistantTranscript = '$_assistantTranscript$delta';
          transcript = _assistantTranscript;
        }
        break;
      case 'tts.audio':
      case 'tts.audio.chunk':
        if (event is RealtimeAudioEvent) {
          if (event.turnId != null && _cancelledTurns.contains(event.turnId)) {
            break;
          }
          final mime = event.details['mime_type']?.toString() ?? 'audio/wav';
          final turnId = event.turnId ?? 'turn';
          try {
            if (_playbackTurnId != null && _playbackTurnId != turnId) {
              await _stopPlaybackQuietly();
            }
            _playbackTurnId = turnId;
            final item = PlaybackItem(
              id: '$turnId-${event.sequence}',
              bytes: event.bytes,
              mimeType: mime,
            );
            if (playback is AudioPlaybackController) {
              await (playback as AudioPlaybackController).append(item);
            } else {
              await playback.enqueue(item);
            }
            if (event.turnId != null &&
                _cancelledTurns.contains(event.turnId)) {
              return;
            }
            if (_isEventCurrent(generation)) state = CallState.speaking;
          } catch (value) {
            if (_isEventCurrent(generation)) {
              error = value.toString();
              state = CallState.failed;
            }
          }
        }
        break;
      case 'turn.completed':
        _activeTurnId = null;
        if (playback.status != PlaybackStatus.playing &&
            playback.status != PlaybackStatus.loading) {
          state = CallState.listening;
        }
        break;
      case 'turn.failed':
        if (!_isEventCurrent(generation)) return;
        error =
            event.details['message']?.toString() ??
            event.details['code']?.toString() ??
            '本轮回复失败';
        _activeTurnId = null;
        _playbackTurnId = null;
        state = CallState.failed;
        await _stopCaptureQuietly();
        await _stopPlaybackQuietly();
        break;
      case 'turn.interrupted':
      case 'turn.cancelled':
        if (!_isEventCurrent(generation)) return;
        _activeTurnId = null;
        _playbackTurnId = null;
        await _stopPlaybackQuietly();
        if (!_isEventCurrent(generation)) return;
        state = CallState.listening;
        break;
      case 'session.expiring':
        _transport?.heartbeat();
        break;
      case 'session.expired':
        if (!_isEventCurrent(generation)) return;
        error = '会话已过期';
        state = CallState.failed;
        await _stopCaptureQuietly();
        await _stopPlaybackQuietly();
        await _closeTransportQuietly();
        await _deactivateAudioSessionQuietly();
        break;
      case 'error':
        if (!_isEventCurrent(generation)) return;
        error = event.details['message']?.toString() ?? '实时连接出错';
        state = CallState.failed;
        unawaited(_stopCaptureQuietly());
        unawaited(_stopPlaybackQuietly());
        unawaited(_deactivateAudioSessionQuietly());
        break;
    }
    if (_isEventCurrent(generation)) _notify();
  }

  void _cancelActiveResponse() {
    final turnId = _activeTurnId;
    if (turnId != null &&
        !_cancelledTurns.contains(turnId) &&
        _transport?.isConnected == true) {
      _transport!.cancel(turnId, playback.playedMilliseconds);
      _cancelledTurns.add(turnId);
    }
    _playbackTurnId = null;
    unawaited(_stopPlaybackQuietly());
    state = CallState.listening;
  }

  String _eventText(RealtimeEvent event) {
    final value = event.details['text'] ?? event.details['delta'];
    return value?.toString() ?? '';
  }

  void _handleTransportError(Object value, StackTrace stack) {
    if (_disposed || _ending) return;
    error = value.toString();
    state = CallState.reconnecting;
    unawaited(_stopCaptureQuietly());
    _notify();
  }

  void _handleCaptureError(Object value, StackTrace stack) {
    if (_disposed || _ending) return;
    error = value.toString();
    state = CallState.reconnecting;
    _notify();
  }

  void _handleTransportDone() {
    if (_disposed || _ending || state == CallState.ended) return;
    if (state != CallState.failed) state = CallState.reconnecting;
    unawaited(_stopCaptureQuietly());
    _notify();
  }

  bool get _isActiveState =>
      state == CallState.listening ||
      state == CallState.thinking ||
      state == CallState.speaking;

  Future<void> _stopCaptureQuietly() async {
    try {
      await capture.stop();
    } catch (_) {
      // Cleanup must continue even when the platform recorder is already down.
    }
  }

  Future<void> _stopPlaybackQuietly() async {
    try {
      await playback.stop();
    } catch (_) {
      // Playback can already be gone after a route or audio-focus change.
    }
  }

  Future<void> _closeTransportQuietly() async {
    try {
      final closeFuture = _transport?.close();
      await closeFuture;
    } catch (_) {
      // A socket can already be closed by the platform/network stack.
    }
  }

  Future<void> _deactivateAudioSessionQuietly() async {
    if (!_audioSessionActive) return;
    _audioSessionActive = false;
    try {
      await _deactivateAudioSession?.call();
    } catch (_) {
      // Audio focus may already have been reclaimed by the operating system.
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  static String _defaultRequestId() =>
      DateTime.now().microsecondsSinceEpoch.toString();

  static String? _stringValue(Map<String, dynamic> map, List<String> keys) {
    for (final key in keys) {
      final value = map[key]?.toString().trim();
      if (value != null && value.isNotEmpty) return value;
    }
    return null;
  }
}
