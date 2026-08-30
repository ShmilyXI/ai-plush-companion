import 'dart:async';
import 'dart:convert';

import 'package:ai_plush_companion/features/chat/data/conversation_realtime_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

class _Channel extends Mock implements WebSocketChannel {}

class _Sink extends Mock implements WebSocketSink {}

void main() {
  late _Channel channel;
  late _Sink sink;
  late StreamController<Object?> incoming;

  setUp(() {
    channel = _Channel();
    sink = _Sink();
    incoming = StreamController<Object?>();
    when(() => channel.stream).thenAnswer((_) => incoming.stream);
    when(() => channel.sink).thenReturn(sink);
    when(() => channel.ready).thenAnswer((_) async {});
    when(() => sink.close(any(), any())).thenAnswer((_) async {});
  });

  tearDown(() async => incoming.close());

  test(
    'opens with bearer subprotocol and sends the session start event',
    () async {
      final client = ConversationRealtimeClient(
        streamUrl: Uri.parse('wss://example.test/stream'),
        runtimeToken: 'runtime-token',
        connector: (uri, {protocols}) {
          expect(protocols, ['bearer.runtime-token']);
          return channel;
        },
      );
      await client.connect(conversationId: 'conversation-1');
      verify(
        () => sink.add(any(that: contains('web.session.start'))),
      ).called(1);
      client.sendText('request-1', '你好');
      verify(() => sink.add(any(that: contains('turn.text')))).called(1);
      await client.close();
    },
  );

  test(
    'sends cancellation with played duration and stream stop once',
    () async {
      final client = ConversationRealtimeClient(
        streamUrl: Uri.parse('wss://example.test'),
        runtimeToken: 'token',
        channel: channel,
      );
      await client.connect();
      client.cancel('turn-1', 420);
      client.stop();
      client.stop();
      final sent = verify(
        () => sink.add(captureAny()),
      ).captured.cast<String>().map(jsonDecode).whereType<Map>().toList();
      expect(
        sent
            .where((item) => item['type'] == 'response.cancel')
            .single['played_ms'],
        420,
      );
      expect(sent.where((item) => item['type'] == 'stream.stop'), hasLength(1));
      await client.close();
    },
  );
}
