import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../domain/realtime_models.dart';
import 'realtime_event_decoder.dart';

typedef WebSocketConnector =
    WebSocketChannel Function(Uri uri, {Iterable<String>? protocols});

class ConversationRealtimeClient {
  ConversationRealtimeClient({
    required this.streamUrl,
    required this.runtimeToken,
    WebSocketChannel? channel,
    WebSocketConnector? connector,
  }) : _channel = channel,
       _connector = connector ?? WebSocketChannel.connect;

  final Uri streamUrl;
  final String runtimeToken;
  final WebSocketConnector _connector;
  WebSocketChannel? _channel;
  StreamSubscription<Object?>? _subscription;
  final _events = StreamController<RealtimeEvent>.broadcast();
  final _outgoing = <Object>[];
  final _decoder = RealtimeEventDecoder();
  int lastSequence = 0;
  bool _connected = false;
  bool _stopped = false;

  Stream<RealtimeEvent> get events => _events.stream;
  bool get isConnected => _connected;

  Future<void> connect({
    String? conversationId,
    String? profileId,
    Map<String, dynamic>? format,
  }) async {
    if (_stopped) throw StateError('client is closed');
    _channel ??= _connector(streamUrl, protocols: ['bearer.$runtimeToken']);
    final channel = _channel!;
    _subscription = channel.stream.listen(
      _onMessage,
      onError: _events.addError,
      onDone: () {
        _connected = false;
      },
    );
    _connected = true;
    _send({
      'type': 'web.session.start',
      'conversation_id': conversationId,
      'profile_id': profileId,
      'format': format ?? const {},
    });
    await Future<void>.delayed(Duration.zero);
  }

  void sendText(String requestId, String text) =>
      _send({'type': 'turn.text', 'request_id': requestId, 'text': text});
  void startAudio(String requestId, {String mimeType = 'audio/wav'}) => _send({
    'type': 'turn.audio.start',
    'request_id': requestId,
    'mime_type': mimeType,
  });
  void pushAudio(Uint8List bytes) => _send(bytes.buffer.asUint8List());
  void endAudio(String requestId) =>
      _send({'type': 'turn.audio.end', 'request_id': requestId});
  void commitAudio(String requestId, int durationMs) => _send({
    'type': 'input.audio.commit',
    'request_id': requestId,
    'duration_ms': durationMs,
  });
  void cancel(String turnId, int playedMs) => _send({
    'type': 'response.cancel',
    'turn_id': turnId,
    'played_ms': playedMs,
  });
  void heartbeat() =>
      _send({'type': 'heartbeat', 'last_sequence': lastSequence});

  void stop() {
    if (_stopped) return;
    _stopped = true;
    _send({'type': 'stream.stop'});
  }

  Future<void> close({String reason = 'client_closed'}) async {
    if (!_stopped) stop();
    await _subscription?.cancel();
    await _channel?.sink.close();
    _connected = false;
    await _events.close();
  }

  void _send(Object message) {
    if (_stopped && message is Map && message['type'] != 'stream.stop') return;
    if (!_connected || _channel == null) {
      _outgoing.add(message);
      return;
    }
    _channel!.sink.add(message is Uint8List ? message : jsonEncode(message));
    if (_outgoing.isNotEmpty) {
      final queued = List<Object>.from(_outgoing);
      _outgoing.clear();
      for (final item in queued) {
        _channel!.sink.add(item is Uint8List ? item : jsonEncode(item));
      }
    }
  }

  void _onMessage(Object? message) {
    try {
      final event = message is String
          ? _decoder.decodeJson(message)
          : _decoder.decodeBinary(
              message is Uint8List
                  ? message
                  : Uint8List.fromList((message as List<int>)),
            );
      lastSequence = event.sequence;
      _events.add(event);
    } catch (error, stack) {
      _events.addError(error, stack);
    }
  }
}
