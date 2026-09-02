import 'dart:convert';

import 'package:ai_plush_companion/features/profiles/data/profile_repository.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test('lists real voice options by TTS model and keeps their IDs', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({
          'code': 0,
          'msg': 'success',
          'data': {
            'list': [
              {'id': 'voice-a', 'name': '女声', 'ttsModelId': 'tts-a'},
            ],
            'total': 1,
          },
        }),
      );
    });
    final repository = ProfileRepository(testApiClient(adapter));

    final voices = await repository.listVoiceOptions('tts-a');

    expect(voices.single['id'], 'voice-a');
    expect(voices.single['name'], '女声');
    expect(adapter.requests.single.path, '/api/v1/voices');
    expect(adapter.requests.single.queryParameters['ttsModelId'], 'tts-a');
  });

  test(
    'uses the app profile facade and decodes a string create response',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        if (request.method == 'POST') {
          return jsonResponse(
            jsonEncode({'code': 0, 'msg': 'success', 'data': 'p-new'}),
          );
        }
        return jsonResponse(
          jsonEncode({'code': 0, 'msg': 'success', 'data': []}),
        );
      });
      final repository = ProfileRepository(testApiClient(adapter));

      final id = await repository.createCustom('我的角色');

      expect(id, 'p-new');
      expect(adapter.requests.single.path, '/app/profiles');
      expect(adapter.requests.single.uri.path, '/xiaozhi/app/profiles');
      expect(adapter.requests.single.data, {
        'source': 'custom',
        'name': '我的角色',
      });
    },
  );

  test(
    'exposes profile memory operations with the shared profile route',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({
            'code': 0,
            'msg': 'success',
            'data': {
              'enabled': false,
              'items': [
                {
                  'id': 'm1',
                  'content': '周末散步',
                  'updatedAt': '2026-08-31T00:00:00Z',
                },
              ],
            },
          }),
        );
      });
      final repository = ProfileMemoryRepository(testApiClient(adapter));

      final view = await repository.list('p1');

      expect(view.enabled, isFalse);
      expect(view.items.single.id, 'm1');
      expect(adapter.requests.single.path, '/companion/profiles/p1/memories');
    },
  );

  test('accepts null success data for profile writes', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({'code': 0, 'msg': 'success', 'data': null}),
      );
    });
    final repository = ProfileRepository(testApiClient(adapter));

    await repository.saveAndActivate('p1', {'agentName': '更新后的角色'});
    await repository.setMemoryEnabled('p1', false);
    await repository.deleteProfile('p1');

    expect(adapter.requests.map((request) => request.method), [
      'PUT',
      'PUT',
      'DELETE',
    ]);
  });

  test('uploads avatars as the required file multipart part', () async {
    final adapter = FakeHttpClientAdapter((request) {
      expect(request.method, 'POST');
      expect(request.path, '/app/profiles/p1/avatar');
      expect(request.data, isA<FormData>());
      final form = request.data as FormData;
      expect(form.files.single.key, 'file');
      return jsonResponse(
        jsonEncode({
          'code': 0,
          'msg': 'success',
          'data': {'url': '/avatar', 'checksum': 'abc'},
        }),
      );
    });
    final repository = ProfileRepository(testApiClient(adapter));

    await repository.saveAvatar('p1', [1, 2, 3], 'image/png');
  });

  test(
    'allows editing and clearing memory while runtime memory is disabled',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({'code': 0, 'msg': 'success', 'data': null}),
        );
      });
      final repository = ProfileMemoryRepository(testApiClient(adapter));

      await repository.update('p1', 'm1', '新的记忆');
      await repository.delete('p1', 'm1');
      await repository.clear('p1');

      expect(adapter.requests.map((request) => request.method), [
        'PUT',
        'DELETE',
        'DELETE',
      ]);
      expect(adapter.requests.first.data, {'content': '新的记忆'});
    },
  );

  test('rejects a memory response without an items list', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({
          'code': 0,
          'msg': 'success',
          'data': {'enabled': true},
        }),
      );
    });
    final repository = ProfileMemoryRepository(testApiClient(adapter));

    expect(() => repository.list('p1'), throwsA(isA<FormatException>()));
  });
}
