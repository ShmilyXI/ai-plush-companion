import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:ai_plush_companion/core/providers/companion_store.dart';
import 'package:ai_plush_companion/features/audio/data/audio_playback_controller.dart';
import 'package:ai_plush_companion/features/audio/domain/playback_state.dart';
import 'package:ai_plush_companion/features/chat/application/chat_controller.dart';
import 'package:ai_plush_companion/features/chat/data/conversation_repository.dart';
import 'package:ai_plush_companion/features/chat/data/conversation_realtime_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

class _Gateway implements ConversationGateway {
  final created = <String>[];

  @override
  Future<Map<String, dynamic>> create(String profileId) async {
    created.add(profileId);
    return {
      'conversationId': 'remote-conversation',
      'agentId': profileId,
      'streamUrl': 'wss://example.test/stream',
      'runtimeToken': 'runtime-token',
    };
  }

  @override
  Future<Map<String, dynamic>> continueConversation(String id) async => {
    'conversationId': id,
    'agentId': 'profile-luna',
    'streamUrl': 'wss://example.test/stream',
    'runtimeToken': 'runtime-token',
  };

  @override
  Future<void> delete(String id) async {}

  @override
  Future<List<Map<String, dynamic>>> history(String id) async => const [];

  @override
  Future<List<Map<String, dynamic>>> list() async => const [];

  @override
  Future<void> rename(String id, String title) async {}
}

class _Channel extends Mock implements WebSocketChannel {}

class _Sink extends Mock implements WebSocketSink {}

class _PlaybackOutput implements AudioOutput {
  final played = <String>[];

  @override
  Future<void> play(PlaybackItem item) async => played.add(item.id);

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async {}
}

class _BlockingPlaybackOutput implements AudioOutput {
  final started = <String>[];
  final releaseFirst = Completer<void>();

  @override
  Future<void> play(PlaybackItem item) async {
    started.add(item.id);
    if (started.length == 1) await releaseFirst.future;
  }

  @override
  Future<void> pause() async {}

  @override
  Future<void> resume() async {}

  @override
  Future<void> stop() async {}
}

void main() {
  test(
    'passes the configured websocket origin to the runtime client',
    () async {
      final gateway = _Gateway();
      final channel = _Channel();
      final sink = _Sink();
      final incoming = StreamController<Object?>();
      when(() => channel.stream).thenAnswer((_) => incoming.stream);
      when(() => channel.sink).thenReturn(sink);
      when(() => channel.ready).thenAnswer((_) async {});
      when(() => sink.close(any(), any())).thenAnswer((_) async {});
      Uri? connectedUrl;
      final store = CompanionStore(demo: true)..conversations = [];
      final controller = ChatController(
        conversations: gateway,
        store: store,
        runtimeWsOrigin: Uri.parse('https://edge.example.test:9443'),
        clientFactory: (url, token) {
          connectedUrl = url;
          return ConversationRealtimeClient(
            streamUrl: url,
            runtimeToken: token,
            channel: channel,
          );
        },
      );

      await controller.sendText('你好');

      expect(connectedUrl, Uri.parse('wss://edge.example.test:9443/stream'));
      await controller.close();
      await incoming.close();
    },
  );

  test(
    'creates a runtime for a new conversation and streams text replies',
    () async {
      final gateway = _Gateway();
      final channel = _Channel();
      final sink = _Sink();
      final incoming = StreamController<Object?>();
      when(() => channel.stream).thenAnswer((_) => incoming.stream);
      when(() => channel.sink).thenReturn(sink);
      when(() => channel.ready).thenAnswer((_) async {});
      when(() => sink.close(any(), any())).thenAnswer((_) async {});

      final store = CompanionStore(demo: true);
      store.conversations = [];
      final controller = ChatController(
        conversations: gateway,
        store: store,
        clientFactory: (_, __) => ConversationRealtimeClient(
          streamUrl: Uri.parse('wss://example.test/stream'),
          runtimeToken: 'runtime-token',
          channel: channel,
        ),
      );

      await controller.sendText('你好');
      expect(gateway.created, ['profile-luna']);
      verify(() => sink.add(any(that: contains('turn.text')))).called(1);

      incoming.add(
        jsonEncode({
          'type': 'turn.started',
          'sequence': 1,
          'turn_id': 'turn-1',
          'details': {'request_id': 'request-1'},
        }),
      );
      incoming.add(
        jsonEncode({
          'type': 'llm.delta',
          'sequence': 2,
          'turn_id': 'turn-1',
          'details': {'text': '收到啦'},
        }),
      );
      await Future<void>.delayed(Duration.zero);
      expect(store.currentConversation.messages.last.text, '收到啦');
      expect(
        store.currentConversation.messages.last.author.toString(),
        contains('assistant'),
      );

      await controller.close();
      await incoming.close();
    },
  );

  test(
    'queues TTS chunks from one turn without truncating the first chunk',
    () async {
      final gateway = _Gateway();
      final channel = _Channel();
      final sink = _Sink();
      final incoming = StreamController<Object?>();
      when(() => channel.stream).thenAnswer((_) => incoming.stream);
      when(() => channel.sink).thenReturn(sink);
      when(() => channel.ready).thenAnswer((_) async {});
      when(() => sink.close(any(), any())).thenAnswer((_) async {});
      final output = _PlaybackOutput();
      final playback = AudioPlaybackController(output: output);
      final store = CompanionStore(demo: true);
      final controller = ChatController(
        conversations: gateway,
        store: store,
        playback: playback,
        clientFactory: (_, __) => ConversationRealtimeClient(
          streamUrl: Uri.parse('wss://example.test/stream'),
          runtimeToken: 'runtime-token',
          channel: channel,
        ),
      );

      await controller.sendText('你好');
      incoming.add(
        jsonEncode({
          'type': 'turn.started',
          'sequence': 1,
          'turn_id': 'turn-1',
        }),
      );
      incoming.add(
        jsonEncode({
          'type': 'tts.audio',
          'sequence': 2,
          'turn_id': 'turn-1',
          'transport': 'binary',
          'byte_length': 1,
          'audio_sequence': 2,
          'mime_type': 'audio/wav',
        }),
      );
      incoming.add(Uint8List.fromList([1]));
      incoming.add(
        jsonEncode({
          'type': 'tts.audio',
          'sequence': 3,
          'turn_id': 'turn-1',
          'transport': 'binary',
          'byte_length': 1,
          'audio_sequence': 3,
          'mime_type': 'audio/wav',
        }),
      );
      incoming.add(Uint8List.fromList([2]));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(playback.queue, hasLength(2));
      expect(output.played, hasLength(1));
      await controller.close();
      await incoming.close();
    },
  );

  test(
    'serializes delayed TTS events so later chunks append to the same turn',
    () async {
      final gateway = _Gateway();
      final channel = _Channel();
      final sink = _Sink();
      final incoming = StreamController<Object?>();
      when(() => channel.stream).thenAnswer((_) => incoming.stream);
      when(() => channel.sink).thenReturn(sink);
      when(() => channel.ready).thenAnswer((_) async {});
      when(() => sink.close(any(), any())).thenAnswer((_) async {});
      final output = _BlockingPlaybackOutput();
      final playback = AudioPlaybackController(output: output);
      final store = CompanionStore(demo: true);
      final controller = ChatController(
        conversations: gateway,
        store: store,
        playback: playback,
        clientFactory: (_, __) => ConversationRealtimeClient(
          streamUrl: Uri.parse('wss://example.test/stream'),
          runtimeToken: 'runtime-token',
          channel: channel,
        ),
      );

      await controller.sendText('你好');
      incoming.add(
        jsonEncode({
          'type': 'turn.started',
          'sequence': 1,
          'turn_id': 'turn-serial',
        }),
      );
      for (final entry in const [(2, 1), (3, 2)]) {
        incoming.add(
          jsonEncode({
            'type': 'tts.audio',
            'sequence': entry.$1,
            'turn_id': 'turn-serial',
            'transport': 'binary',
            'byte_length': 1,
            'audio_sequence': entry.$1,
            'mime_type': 'audio/wav',
          }),
        );
        incoming.add(Uint8List.fromList([entry.$2]));
      }

      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect(output.started, hasLength(1));
      output.releaseFirst.complete();
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(playback.queue, hasLength(2));
      expect(output.started, hasLength(1));
      await controller.close();
      await incoming.close();
    },
  );

  test('manual playback includes every TTS chunk for a response', () async {
    final gateway = _Gateway();
    final channel = _Channel();
    final sink = _Sink();
    final incoming = StreamController<Object?>();
    when(() => channel.stream).thenAnswer((_) => incoming.stream);
    when(() => channel.sink).thenReturn(sink);
    when(() => channel.ready).thenAnswer((_) async {});
    when(() => sink.close(any(), any())).thenAnswer((_) async {});
    final output = _PlaybackOutput();
    final playback = AudioPlaybackController(output: output);
    final store = CompanionStore(demo: true)..autoPlay = false;
    final controller = ChatController(
      conversations: gateway,
      store: store,
      playback: playback,
      clientFactory: (_, __) => ConversationRealtimeClient(
        streamUrl: Uri.parse('wss://example.test/stream'),
        runtimeToken: 'runtime-token',
        channel: channel,
      ),
    );

    await controller.sendText('你好');
    incoming.add(
      jsonEncode({
        'type': 'turn.started',
        'sequence': 1,
        'turn_id': 'turn-manual',
      }),
    );
    incoming.add(
      jsonEncode({
        'type': 'llm.delta',
        'sequence': 2,
        'turn_id': 'turn-manual',
        'details': {'text': '回复'},
      }),
    );
    for (final entry in const [(3, 1), (4, 2)]) {
      incoming.add(
        jsonEncode({
          'type': 'tts.audio',
          'sequence': entry.$1,
          'turn_id': 'turn-manual',
          'transport': 'binary',
          'byte_length': 1,
          'audio_sequence': entry.$1,
          'mime_type': 'audio/wav',
        }),
      );
      incoming.add(Uint8List.fromList([entry.$2]));
    }
    await Future<void>.delayed(const Duration(milliseconds: 20));

    await controller.toggleAudio('assistant-turn-manual');
    await playback.completeCurrent();

    expect(output.played, [
      'assistant-turn-manual-3',
      'assistant-turn-manual-4',
    ]);
    await controller.close();
    await incoming.close();
  });
}
