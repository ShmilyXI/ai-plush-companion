import 'dart:typed_data';

import '../../chat/data/conversation_realtime_client.dart';
import '../../chat/domain/realtime_models.dart';

abstract interface class CallTransport {
  Stream<RealtimeEvent> get events;
  bool get isConnected;

  Future<void> connect({
    String? conversationId,
    String? profileId,
    Map<String, dynamic>? format,
  });

  void pushAudio(Uint8List bytes);
  void commitAudio(String requestId, int durationMs);
  void cancel(String turnId, int playedMs);
  void heartbeat();
  void stop();
  Future<void> close();
}

/// Keeps the call state machine independent from the concrete WebSocket
/// implementation, while preserving the existing public conversation client.
class ConversationCallTransport implements CallTransport {
  ConversationCallTransport(this.client);
  final ConversationRealtimeClient client;

  @override
  Stream<RealtimeEvent> get events => client.events;

  @override
  bool get isConnected => client.isConnected;

  @override
  Future<void> connect({
    String? conversationId,
    String? profileId,
    Map<String, dynamic>? format,
  }) => client.connect(
    conversationId: conversationId,
    profileId: profileId,
    format: format,
  );

  @override
  void pushAudio(Uint8List bytes) => client.pushAudio(bytes);

  @override
  void commitAudio(String requestId, int durationMs) =>
      client.commitAudio(requestId, durationMs);

  @override
  void cancel(String turnId, int playedMs) => client.cancel(turnId, playedMs);

  @override
  void heartbeat() => client.heartbeat();

  @override
  void stop() => client.stop();

  @override
  Future<void> close() => client.close();
}
