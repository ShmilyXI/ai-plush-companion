import 'dart:async';

import 'package:audio_service/audio_service.dart' as audio;
import 'package:audio_session/audio_session.dart';
import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';

import 'companion_audio_handler.dart';
import 'audio_playback_controller.dart';
import '../domain/playback_state.dart' as local;

typedef CompanionHandlerInitializer = Future<CompanionAudioHandler> Function();
typedef AudioSessionConfigurator = Future<void> Function();

/// Owns the process-wide audio service registration and the shared reply
/// player. It is safe to call [initialize] from app startup more than once.
class CompanionAudioService {
  CompanionAudioService({
    CompanionHandlerInitializer? handlerInitializer,
    AudioSessionConfigurator? sessionConfigurator,
  }) : _handlerInitializer = handlerInitializer ?? _initializePlatformHandler,
       _sessionConfigurator = sessionConfigurator;

  static final CompanionAudioService shared = CompanionAudioService();

  final CompanionHandlerInitializer _handlerInitializer;
  final AudioSessionConfigurator? _sessionConfigurator;
  CompanionAudioHandler? _handler;
  Future<CompanionAudioHandler>? _initializing;
  StreamSubscription<AudioInterruptionEvent>? _interruptionSubscription;
  StreamSubscription<void>? _noisySubscription;
  final _interruptionEvents = StreamController<bool>.broadcast();
  AudioSession? _session;
  bool _platformReady = false;
  bool _disposed = false;
  bool _resumeAfterInterruption = false;

  CompanionAudioHandler? get handler => _handler;
  AudioPlaybackController? get playback => _handler?.playback;
  AudioPlaybackController get playbackOrCreate {
    return (_handler ??= CompanionAudioHandler()).playback;
  }

  bool get platformReady => _platformReady;
  Stream<bool> get interruptionEvents => _interruptionEvents.stream;
  Stream<void> get stopRequests =>
      _handler?.stopRequests ?? const Stream<void>.empty();

  static Future<CompanionAudioHandler> initializeShared() =>
      shared.initialize();

  static AudioPlaybackController? get initializedPlayback => shared.playback;

  Future<CompanionAudioHandler> initialize() {
    if (_handler != null) return Future.value(_handler!);
    final pending = _initializing;
    if (pending != null) return pending;
    final future = _initializeInternal();
    _initializing = future;
    future.whenComplete(() {
      if (identical(_initializing, future)) _initializing = null;
    });
    return future;
  }

  Future<void> stop() async {
    await _handler?.stop();
    _resumeAfterInterruption = false;
    try {
      await _session?.setActive(false);
    } catch (_) {
      // The platform may already have released the audio focus.
    }
  }

  Future<void> configureForPlayback() async {
    final session = await _ensureSession();
    if (session == null) return;
    try {
      await session.configure(const AudioSessionConfiguration.speech());
    } catch (_) {
      // Unsupported platforms expose no native audio session.
    }
  }

  Future<bool> configureForCall() async {
    final session = await _ensureSession();
    if (session == null) return true;
    try {
      await session.configure(_callSessionConfiguration);
      return await session.setActive(true);
    } catch (_) {
      // Keep simulator/web tests usable; native failures are reported by the
      // recorder or player when they actually start.
      return true;
    }
  }

  /// Routes call audio to the built-in speaker when enabled. A route change is
  /// best effort because desktop, web, and some simulator hosts do not expose
  /// a native communication route.
  Future<bool> setSpeakerphoneOn(bool enabled) async {
    if (kIsWeb) return true;
    try {
      switch (defaultTargetPlatform) {
        case TargetPlatform.android:
          await AndroidAudioManager().setSpeakerphoneOn(enabled);
          return true;
        case TargetPlatform.iOS:
          await AVAudioSession().overrideOutputAudioPort(
            enabled
                ? AVAudioSessionPortOverride.speaker
                : AVAudioSessionPortOverride.none,
          );
          return true;
        case TargetPlatform.fuchsia:
        case TargetPlatform.linux:
        case TargetPlatform.macOS:
        case TargetPlatform.windows:
          return true;
      }
    } catch (_) {
      return false;
    }
  }

  Future<void> deactivateCall() async {
    final session = _session;
    if (session == null) return;
    try {
      await session.setActive(false);
      await configureForPlayback();
    } catch (_) {
      // The audio route may already have been torn down by the OS.
    }
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    await _interruptionSubscription?.cancel();
    await _noisySubscription?.cancel();
    _interruptionSubscription = null;
    _noisySubscription = null;
    await _handler?.disposeHandler();
    _handler = null;
    _session = null;
    await _interruptionEvents.close();
  }

  Future<CompanionAudioHandler> _initializeInternal() async {
    if (_disposed) throw StateError('audio service is disposed');
    try {
      _handler = await _handlerInitializer();
      _platformReady = true;
    } catch (_) {
      // Unit tests, web, and hosts without a registered native service still
      // get a functional in-process player rather than losing TTS entirely.
      _handler = CompanionAudioHandler();
      _platformReady = false;
    }
    try {
      final configureSession = _sessionConfigurator;
      if (configureSession != null) {
        await configureSession();
      } else {
        await _configureDefaultSession();
      }
    } catch (_) {
      // Session setup is best effort; playback remains available in-process.
    }
    await _requestNotificationPermission();
    return _handler!;
  }

  Future<void> _requestNotificationPermission() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    try {
      await Permission.notification.request();
    } catch (_) {
      // Notification permission is unavailable on older Android versions.
    }
  }

  Future<AudioSession?> _ensureSession() async {
    if (_session != null) return _session;
    try {
      _session = await AudioSession.instance;
      return _session;
    } catch (_) {
      return null;
    }
  }

  Future<void> _configureDefaultSession() async {
    final session = await _ensureSession();
    if (session == null) return;
    await session.configure(const AudioSessionConfiguration.speech());
    await _interruptionSubscription?.cancel();
    await _noisySubscription?.cancel();
    _interruptionSubscription = session.interruptionEventStream.listen((event) {
      final handler = _handler;
      if (handler == null) return;
      if (event.begin) {
        _interruptionEvents.add(true);
        _resumeAfterInterruption =
            handler.playback.status == local.PlaybackStatus.playing;
        unawaited(handler.pauseForInterruption());
      } else {
        _interruptionEvents.add(false);
        if (_resumeAfterInterruption) {
          _resumeAfterInterruption = false;
          unawaited(handler.resumeAfterInterruption());
        }
      }
    });
    _noisySubscription = session.becomingNoisyEventStream.listen((_) {
      unawaited(_handler?.pause());
    });
  }

  static Future<CompanionAudioHandler> _initializePlatformHandler() =>
      audio.AudioService.init<CompanionAudioHandler>(
        builder: CompanionAudioHandler.new,
        config: const audio.AudioServiceConfig(
          androidNotificationChannelId: 'com.xiaozhi.ai_plush_companion.audio',
          androidNotificationChannelName: 'AI 陪伴语音',
          androidNotificationChannelDescription: 'AI 回复和通话音频',
          androidNotificationOngoing: false,
          androidStopForegroundOnPause: false,
        ),
      );

  static final _callSessionConfiguration = AudioSessionConfiguration(
    avAudioSessionCategory: AVAudioSessionCategory.playAndRecord,
    avAudioSessionCategoryOptions:
        AVAudioSessionCategoryOptions.allowBluetooth |
        AVAudioSessionCategoryOptions.defaultToSpeaker,
    avAudioSessionMode: AVAudioSessionMode.voiceChat,
    androidAudioAttributes: AndroidAudioAttributes(
      contentType: AndroidAudioContentType.speech,
      usage: AndroidAudioUsage.voiceCommunication,
    ),
    androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
    androidWillPauseWhenDucked: false,
  );
}
