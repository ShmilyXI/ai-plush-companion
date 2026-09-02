import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/config/app_config.dart';
import '../../../core/providers/companion_store.dart';
import '../../audio/data/audio_capture_controller.dart';
import '../../audio/data/audio_playback_controller.dart';
import '../../audio/data/audio_turn_sender.dart';
import '../../audio/domain/playback_state.dart';
import '../data/conversation_realtime_client.dart';
import '../data/conversation_repository.dart';
import '../domain/chat_models.dart';
import '../domain/realtime_models.dart';

typedef ChatClientFactory =
    ConversationRealtimeClient Function(Uri streamUrl, String runtimeToken);

/// Owns one App conversation runtime and mirrors its durable state into the UI
/// store. Pages use this boundary instead of calling Dio or WebSocket APIs.
class ChatController extends ChangeNotifier {
  ChatController({
    required this.conversations,
    this.store,
    this.demo = false,
    this.runtimeWsOrigin,
    this.clientFactory,
    AudioCapture? capture,
    AudioPlaybackController? playback,
  }) : _capture = capture,
       _playback = playback,
       _ownsCapture = capture == null,
       _ownsPlayback = playback == null {
    if (playback != null) _attachPlayback(playback);
  }

  final ConversationGateway conversations;
  final CompanionStore? store;
  final bool demo;
  final Uri? runtimeWsOrigin;
  final ChatClientFactory? clientFactory;
  AudioCapture? _capture;
  AudioPlaybackController? _playback;
  final bool _ownsCapture;
  final bool _ownsPlayback;
  AudioTurnSender? _audioSender;
  String? _audioConversationId;
  ConversationRealtimeClient? _client;
  StreamSubscription<RealtimeEvent>? _events;
  int _generation = 0;
  String? _activeConversationId;
  String? _activeTurnId;
  String? _activeRequestId;
  String? _pendingVoiceRequestId;
  final Map<String, String> _assistantDrafts = <String, String>{};
  final Map<String, List<PlaybackItem>> _audioItems =
      <String, List<PlaybackItem>>{};
  String? _playbackTurnId;
  String? _playingMessageId;
  PlaybackStatusListener? _playbackListener;
  bool _disposed = false;
  bool _closing = false;
  Future<void> _eventChain = Future<void>.value();

  bool loading = false;
  bool sending = false;
  String? error;
  String? runtimeToken;
  String? streamUrl;

  bool get isConnected => _client?.isConnected ?? false;
  bool get isRecording => _audioSender?.isRecording ?? false;
  int get recordingDurationMs => _audioSender?.durationMs ?? 0;
  AudioPlaybackController? get playback => _playback;
  String? get activeTurnId => _activeTurnId;
  VoiceMessageSender get voiceSender => _ChatVoiceMessageSender(this);

  Future<void> open(String id) async {
    final generation = ++_generation;
    await _closeRuntime();
    loading = true;
    error = null;
    _notify();
    try {
      final history = await conversations.history(id);
      final runtime = await conversations.continueConversation(id);
      if (generation != _generation) return;
      await _connect(id, runtime);
      _replaceHistory(id, history);
    } catch (value) {
      if (generation == _generation) _setError(_errorText(value));
    } finally {
      if (generation == _generation) {
        loading = false;
        _notify();
      }
    }
  }

  Future<void> sendText(String text) async {
    final clean = text.trim();
    if (clean.isEmpty || sending) return;
    if (demo) {
      await store?.sendMessage(clean);
      return;
    }
    try {
      await _ensureConnected();
      final requestId = _requestId();
      _activeRequestId = requestId;
      _setSending(true);
      _appendUser(clean);
      _notify();
      _client!.sendText(requestId, clean);
    } catch (value) {
      _setSending(false);
      _setError(_errorText(value));
    }
  }

  Future<bool> startVoice() async {
    if (demo) return true;
    try {
      await _ensureConnected();
      final client = _client;
      if (client == null || !client.isConnected) return false;
      final conversationId = _activeConversationId;
      if (_audioSender == null || _audioConversationId != conversationId) {
        await _audioSender?.dispose();
        _capture ??= AudioCaptureController();
        _audioSender = AudioTurnSender(
          capture: _capture!,
          transport: ConversationAudioTurnTransport(client),
          mimeType: 'audio/pcm',
        );
        _audioConversationId = conversationId;
      }
      return await _audioSender!.start();
    } catch (value) {
      _setError(_errorText(value));
      return false;
    }
  }

  Future<AudioTurnResult?> finishVoice({bool cancelled = false}) async {
    if (demo) {
      if (!cancelled) store?.addVoiceMessage();
      return null;
    }
    final sender = _audioSender;
    if (sender == null) return null;
    final result = await sender.stop(cancelled: cancelled);
    if (result == null) return null;
    _pendingVoiceRequestId = result.requestId;
    _setSending(true);
    _notify();
    return result;
  }

  Future<void> newConversation() async {
    ++_generation;
    await _closeRuntime();
    store?.newConversation();
    _clearTurnState();
  }

  Future<void> selectConversation(String id) async {
    store?.selectConversation(id);
    await open(id);
  }

  Future<void> startNewForProfile(String profileId) async {
    ++_generation;
    await _closeRuntime();
    store?.selectProfile(profileId);
    _clearTurnState();
  }

  Future<void> rename(String id, String title) async {
    final clean = title.trim();
    if (clean.isEmpty) return;
    if (!demo) await conversations.rename(id, clean);
    store?.renameConversation(id, clean);
  }

  Future<void> delete(String id) async {
    if (store?.currentConversationId == id) await _closeRuntime();
    if (!demo) await conversations.delete(id);
    store?.deleteConversation(id);
  }

  Future<void> toggleAudio(String messageId) async {
    final items = _audioItems[messageId];
    if (items == null || items.isEmpty) {
      store?.toggleAudio(messageId);
      return;
    }
    _attachPlayback(_playback ??= AudioPlaybackController());
    if (_playingMessageId == messageId &&
        _playback!.status == PlaybackStatus.playing) {
      await _playback!.stop();
      store?.setAudioPlaying(messageId, false);
      return;
    }
    final queued = List<PlaybackItem>.of(items);
    await _playback!.enqueue(queued.first);
    for (final item in queued.skip(1)) {
      await _playback!.append(item);
    }
    _playingMessageId = messageId;
    store?.setAudioPlaying(messageId, true);
  }

  /// Returns runtime metadata for the selected persistent conversation.
  Future<Map<String, dynamic>> prepareRuntime() async {
    if (demo) throw StateError('演示模式不支持实时通话');
    await _ensureConnected();
    final runtime = <String, dynamic>{
      'conversationId': _activeConversationId,
      'runtimeToken': runtimeToken,
      'streamUrl': streamUrl,
    };
    await _closeRuntime();
    return runtime;
  }

  Future<void> close() async {
    ++_generation;
    await _closeRuntime();
    if (_audioSender != null) {
      if (_ownsCapture) {
        await _audioSender!.dispose();
      } else {
        await _audioSender!.cancel();
      }
    }
    _audioSender = null;
    if (_ownsCapture) await _capture?.dispose();
    _capture = null;
    final playback = _playback;
    final listener = _playbackListener;
    if (playback != null && listener != null) {
      playback.removeStatusListener(listener);
    }
    _playbackListener = null;
    if (_ownsPlayback) {
      await playback?.dispose();
      _playback = null;
    } else {
      // The process-wide audio service owns this player. Keep the instance so
      // a later login can reattach its status listener without losing the
      // lock-screen queue.
      await playback?.stop();
    }
    _playingMessageId = null;
    _audioItems.clear();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(close());
    super.dispose();
  }

  Future<void> _ensureConnected() async {
    final current = store?.currentConversation;
    if (current == null) throw StateError('当前会话不存在');
    if (isConnected && _activeConversationId == current.id) return;
    final profile = store?.selectedProfile;
    if (profile == null ||
        profile.id.isEmpty ||
        profile.id == 'profile-empty') {
      throw StateError('请先选择陪伴角色');
    }
    Map<String, dynamic> runtime;
    var conversationId = current.id;
    if (_isLocalConversation(conversationId)) {
      runtime = await conversations.create(profile.id);
      final remoteId = _stringValue(runtime, const [
        'conversationId',
        'conversation_id',
        'id',
      ]);
      if (remoteId == null || remoteId.isEmpty) {
        throw StateError('创建会话响应不完整');
      }
      conversationId = remoteId;
      final replacement = CompanionConversation(
        id: remoteId,
        profileId: profile.id,
        title: current.title,
        updatedAt: current.updatedAt,
        messages: current.messages,
        source: current.source,
      );
      store?.replaceConversationId(current.id, replacement);
    } else {
      runtime = await conversations.continueConversation(conversationId);
    }
    await _connect(conversationId, runtime);
  }

  Future<void> _connect(
    String conversationId,
    Map<String, dynamic> runtime,
  ) async {
    final token = _stringValue(runtime, const [
      'runtimeToken',
      'runtime_token',
    ]);
    final url = _stringValue(runtime, const ['streamUrl', 'stream_url']);
    if (token == null || token.isEmpty || url == null || url.isEmpty) {
      throw StateError('运行时会话响应不完整');
    }
    await _closeRuntime();
    final resolvedUrl = AppConfig.rewriteRuntimeStreamUrl(
      Uri.parse(url),
      runtimeWsOrigin,
    );
    runtimeToken = token;
    streamUrl = resolvedUrl.toString();
    final client =
        clientFactory?.call(resolvedUrl, token) ??
        ConversationRealtimeClient(streamUrl: resolvedUrl, runtimeToken: token);
    _client = client;
    _activeConversationId = conversationId;
    _events = client.events.listen(
      _onEvent,
      onError: (Object value, StackTrace stack) {
        _setError(value.toString());
      },
      onDone: () {
        if (_client == client) {
          _client = null;
          _activeConversationId = null;
        }
      },
    );
    await client.connect(conversationId: conversationId);
  }

  Future<void> _closeRuntime() async {
    _closing = true;
    try {
      final events = _events;
      _events = null;
      final client = _client;
      _client = null;
      _activeConversationId = null;
      await events?.cancel();
      try {
        await _eventChain.timeout(const Duration(milliseconds: 500));
      } catch (_) {
        // A provider playback callback may be in flight. It will observe the
        // detached client/generation before touching the store.
      }
      if (client != null) await client.close();
      await _playback?.stop();
      runtimeToken = null;
      streamUrl = null;
      _playbackTurnId = null;
      _clearTurnState();
    } finally {
      _closing = false;
    }
  }

  void _onEvent(RealtimeEvent event) {
    final client = _client;
    if (client == null) return;
    final generation = _generation;
    _eventChain = _eventChain.then<void>((_) async {
      if (!_eventIsCurrent(client, generation)) return;
      try {
        await _processEvent(event, client, generation);
      } catch (value) {
        if (_eventIsCurrent(client, generation)) _setError(_errorText(value));
      }
    });
  }

  bool _eventIsCurrent(ConversationRealtimeClient client, int generation) =>
      !_closing && _generation == generation && identical(_client, client);

  Future<void> _processEvent(
    RealtimeEvent event,
    ConversationRealtimeClient client,
    int generation,
  ) async {
    if (!_eventIsCurrent(client, generation)) return;
    switch (event.type) {
      case 'turn.started':
        _activeTurnId = event.turnId;
        _activeRequestId =
            event.details['request_id']?.toString() ?? _activeRequestId;
        break;
      case 'asr.final':
        final text = _eventText(event);
        if (text.isNotEmpty && _pendingVoiceRequestId != null) {
          _appendUser(
            text,
            id: 'voice-${event.turnId ?? _pendingVoiceRequestId}',
          );
          _pendingVoiceRequestId = null;
        }
        break;
      case 'llm.delta':
        final text = _eventText(event);
        if (text.isNotEmpty) {
          final id = 'assistant-${event.turnId ?? event.sequence}';
          final draft = '${_assistantDrafts[id] ?? ''}$text';
          _assistantDrafts[id] = draft;
          _upsertAssistant(id, draft);
        }
        break;
      case 'tts.audio':
      case 'tts.audio.chunk':
        if (event is RealtimeAudioEvent) {
          final id = 'assistant-${event.turnId ?? event.sequence}';
          _markAssistantAudio(id);
          final item = PlaybackItem(
            id: '$id-${event.sequence}',
            bytes: event.bytes,
            mimeType: event.details['mime_type']?.toString() ?? 'audio/wav',
          );
          _audioItems.putIfAbsent(id, () => <PlaybackItem>[]).add(item);
          if (store?.autoPlay ?? true) {
            _attachPlayback(_playback ??= AudioPlaybackController());
            _playingMessageId = id;
            final turnId = event.turnId ?? _activeTurnId;
            final sameTurn = turnId != null && _playbackTurnId == turnId;
            if (!sameTurn) {
              await _playback!.enqueue(item);
              if (!_eventIsCurrent(client, generation)) return;
              _playbackTurnId = turnId;
            } else if (_playback!.current != null) {
              await _playback!.append(item);
              if (!_eventIsCurrent(client, generation)) return;
            } else {
              await _playback!.enqueue(item);
              if (!_eventIsCurrent(client, generation)) return;
            }
            store?.setAudioPlaying(id, true);
          }
        }
        break;
      case 'turn.completed':
        _setSending(false);
        _activeTurnId = null;
        _activeRequestId = null;
        break;
      case 'turn.cancelled':
      case 'turn.interrupted':
        _setSending(false);
        _activeTurnId = null;
        _playbackTurnId = null;
        await _playback?.stop();
        if (!_eventIsCurrent(client, generation)) return;
        break;
      case 'session.expiring':
        client.heartbeat();
        break;
      case 'session.expired':
        _setSending(false);
        _setError('会话已过期，请重新打开对话');
        break;
      case 'error':
      case 'turn.failed':
        _setSending(false);
        _setError(event.details['message']?.toString() ?? '实时连接出错');
        break;
    }
    if (_eventIsCurrent(client, generation)) _notify();
  }

  void _replaceHistory(String id, List<Map<String, dynamic>> rows) {
    final messages = <ChatMessage>[];
    for (final row in rows) {
      final userText = _stringValue(row, const [
        'text',
        'userText',
        'user_text',
      ]);
      final reply = _stringValue(row, const [
        'reply',
        'assistantText',
        'assistant_text',
      ]);
      final occurred = _dateValue(row['occurred_at'] ?? row['occurredAt']);
      if (userText != null && userText.isNotEmpty) {
        messages.add(
          ChatMessage(
            id: 'history-user-${messages.length}',
            text: userText,
            author: MessageAuthor.user,
            createdAt: occurred,
          ),
        );
      }
      if (reply != null && reply.isNotEmpty) {
        messages.add(
          ChatMessage(
            id: 'history-assistant-${messages.length}',
            text: reply,
            author: MessageAuthor.assistant,
            createdAt: occurred,
          ),
        );
      }
    }
    final current = store?.conversations
        .where((conversation) => conversation.id == id)
        .firstOrNull;
    if (current != null) {
      store?.replaceConversation(current.copyWith(messages: messages));
    }
  }

  void _appendUser(String text, {String? id}) {
    if (store == null) return;
    store!.appendCurrentMessage(
      ChatMessage(
        id: id ?? 'message-${DateTime.now().microsecondsSinceEpoch}',
        text: text,
        author: MessageAuthor.user,
        createdAt: DateTime.now(),
      ),
    );
  }

  void _upsertAssistant(String id, String text) {
    store?.upsertCurrentMessage(messageId: id, text: text);
  }

  void _markAssistantAudio(String id) {
    final current = store?.currentConversation.messages
        .where((message) => message.id == id)
        .firstOrNull;
    if (current != null) {
      store?.upsertCurrentMessage(
        messageId: id,
        text: current.text,
        hasAudio: true,
      );
    }
  }

  void _clearTurnState() {
    _activeTurnId = null;
    _activeRequestId = null;
    _pendingVoiceRequestId = null;
    _assistantDrafts.clear();
    _setSending(false);
  }

  void _setSending(bool value) {
    sending = value;
    if (!_disposed) store?.setSending(value);
  }

  void _setError(String message) {
    error = message;
    store?.setError(message);
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void _attachPlayback(AudioPlaybackController playback) {
    if (identical(_playback, playback) && _playbackListener != null) return;
    final previous = _playback;
    final previousListener = _playbackListener;
    if (previous != null && previousListener != null) {
      previous.removeStatusListener(previousListener);
    }
    _playback = playback;
    final listener = _onPlaybackStatus;
    _playbackListener = listener;
    playback.addStatusListener(listener);
  }

  void _onPlaybackStatus(PlaybackStatus status) {
    final messageId = _playingMessageId;
    if (messageId == null) return;
    if (status == PlaybackStatus.idle ||
        status == PlaybackStatus.completed ||
        status == PlaybackStatus.failed) {
      _playingMessageId = null;
      store?.setAudioPlaying(messageId, false);
    }
  }

  String _errorText(Object value) {
    final text = value.toString();
    return text.startsWith('ApiException(') ? '请求失败，请稍后重试' : text;
  }

  String _requestId() => DateTime.now().microsecondsSinceEpoch.toString();

  static bool _isLocalConversation(String id) =>
      id == 'conversation-empty' || id.startsWith('conversation-');

  static String? _stringValue(Map<String, dynamic> map, List<String> keys) {
    for (final key in keys) {
      final value = map[key];
      if (value != null && value.toString().isNotEmpty) return value.toString();
    }
    return null;
  }

  static DateTime _dateValue(Object? value) {
    if (value is num) return DateTime.fromMillisecondsSinceEpoch(value.toInt());
    return DateTime.tryParse(value?.toString() ?? '') ?? DateTime.now();
  }

  static String _eventText(RealtimeEvent event) =>
      (event.details['text'] ?? event.details['delta'])?.toString() ?? '';
}

class _ChatVoiceMessageSender implements VoiceMessageSender {
  const _ChatVoiceMessageSender(this.controller);

  final ChatController controller;

  @override
  bool get isRecording => controller.isRecording;

  @override
  Future<bool> start() => controller.startVoice();

  @override
  Future<AudioTurnResult?> stop({bool cancelled = false}) =>
      controller.finishVoice(cancelled: cancelled);
}
