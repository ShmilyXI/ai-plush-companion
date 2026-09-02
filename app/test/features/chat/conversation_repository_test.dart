import 'dart:convert';

import 'package:ai_plush_companion/features/chat/data/conversation_repository.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test(
    'creates a conversation with text and audio scopes for the mobile client',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        expect(request.path, '/api/v1/conversations');
        expect(request.method, 'POST');
        expect(request.data, {
          'agentId': 'profile-a',
          'profileId': 'profile-a',
          'inputModes': ['text', 'audio'],
          'outputModes': ['text', 'audio'],
        });
        return jsonResponse(
          jsonEncode({
            'code': 0,
            'msg': 'success',
            'data': {'conversationId': 'c1'},
          }),
        );
      });

      await ConversationRepository(testApiClient(adapter)).create('profile-a');
    },
  );

  test('decodes the owner-scoped history list response', () async {
    final adapter = FakeHttpClientAdapter((request) {
      expect(request.path, '/api/v1/conversations/c1/history');
      return jsonResponse(
        jsonEncode({
          'code': 0,
          'msg': 'success',
          'data': [
            {'turn_id': 't1', 'text': '你好', 'reply': '你好呀'},
          ],
        }),
      );
    });

    final rows = await ConversationRepository(
      testApiClient(adapter),
    ).history('c1');

    expect(rows.single['turn_id'], 't1');
  });
}
