import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/conversation_realtime_client.dart';
import '../domain/chat_models.dart';
import '../domain/realtime_models.dart';

enum ConversationConnectionState {
  idle,
  connecting,
  connected,
  reconnecting,
  closed,
  failed,
}

class ConversationController extends ChangeNotifier {
  ConversationController({this.clientFactory});
  final ConversationRealtimeClient Function(
    String conversationId,
    String runtimeToken,
  )?
  clientFactory;
  ConversationRealtimeClient? _client;
  StreamSubscription<RealtimeEvent>? _events;
  ConversationConnectionState connectionState =
      ConversationConnectionState.idle;
  String? conversationId;
  String? activeTurnId;
  String? error;
  final messages = <ChatMessage>[];

  Future<void> open({
    required String conversationId,
    required String runtimeToken,
    Uri? streamUrl,
  }) async {
    await close();
    this.conversationId = conversationId;
    connectionState = ConversationConnectionState.connecting;
    notifyListeners();
    try {
      final client =
          clientFactory?.call(conversationId, runtimeToken) ??
          ConversationRealtimeClient(
            streamUrl: streamUrl ?? Uri.parse('ws://127.0.0.1/stream'),
            runtimeToken: runtimeToken,
          );
      _client = client;
      _events = client.events.listen(
        _handleEvent,
        onError: (Object value) {
          error = value.toString();
          connectionState = ConversationConnectionState.failed;
          notifyListeners();
        },
      );
      await client.connect(conversationId: conversationId);
      connectionState = ConversationConnectionState.connected;
    } catch (value) {
      error = value.toString();
      connectionState = ConversationConnectionState.failed;
    }
    notifyListeners();
  }

  void sendText(String text) {
    final requestId = DateTime.now().microsecondsSinceEpoch.toString();
    _client?.sendText(requestId, text);
    messages.add(
      ChatMessage(
        id: requestId,
        text: text,
        author: MessageAuthor.user,
        createdAt: DateTime.now(),
      ),
    );
    notifyListeners();
  }

  void commitAudio(String requestId, int durationMs) =>
      _client?.commitAudio(requestId, durationMs);
  void cancelResponse(int playedMs) {
    final turn = activeTurnId;
    if (turn != null) _client?.cancel(turn, playedMs);
  }

  void startNewForProfile(String profileId) {
    conversationId = null;
    activeTurnId = null;
    messages.clear();
    notifyListeners();
  }

  Future<void> close() async {
    await _events?.cancel();
    _events = null;
    await _client?.close();
    _client = null;
    connectionState = ConversationConnectionState.closed;
  }

  void _handleEvent(RealtimeEvent event) {
    switch (event.type) {
      case 'turn.started':
        activeTurnId = event.turnId;
        break;
      case 'llm.delta':
        final text =
            event.details['text'] as String? ??
            event.details['delta'] as String? ??
            '';
        if (text.isNotEmpty) {
          messages.add(
            ChatMessage(
              id: 'assistant-${event.sequence}',
              text: text,
              author: MessageAuthor.assistant,
              createdAt: DateTime.now(),
            ),
          );
        }
        break;
      case 'turn.completed':
        activeTurnId = null;
        break;
      case 'session.expiring':
        _client?.heartbeat();
        break;
      case 'session.expired':
        connectionState = ConversationConnectionState.failed;
        error = '会话已过期';
        break;
      case 'error':
        error = event.details['message'] as String? ?? '实时连接出错';
        connectionState = ConversationConnectionState.failed;
        break;
    }
    notifyListeners();
  }
}
