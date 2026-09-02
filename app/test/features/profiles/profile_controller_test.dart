import 'dart:convert';

import 'package:ai_plush_companion/features/profiles/application/profile_controller.dart';
import 'package:ai_plush_companion/features/profiles/data/profile_repository.dart';
import 'package:ai_plush_companion/features/profiles/domain/profile_models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test('loads remote profiles and removes consumer-deleted rows', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({
          'code': 0,
          'msg': 'success',
          'data': [
            {'id': 'p1', 'name': '远程角色', 'memoryEnabled': 1},
            {
              'id': 'p2',
              'name': '隐藏角色',
              'consumerDeletedAt': '2026-08-30T00:00:00Z',
            },
          ],
        }),
      );
    });
    final controller = ProfileController();

    final loaded = await controller.load(
      ProfileRepository(testApiClient(adapter)),
    );

    expect(loaded, isTrue);
    expect(controller.profiles.map((item) => item.id), ['p1']);
  });

  test(
    'remote save appends a newly created profile to the local list',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({'code': 0, 'msg': 'success', 'data': null}),
        );
      });
      final controller = ProfileController();
      final profile = const CompanionProfile(
        id: 'p1',
        name: '新角色',
        summary: '',
        personality: '',
        systemPrompt: '',
        voice: '默认音色',
        capabilities: {},
        memoryEnabled: true,
        source: ProfileSource.custom,
      );

      final saved = await controller.saveRemote(
        ProfileRepository(testApiClient(adapter)),
        profile,
      );

      expect(saved, isTrue);
      expect(controller.profiles.single.id, 'p1');
    },
  );

  test('keeps the active profile when the server rejects a save', () async {
    final adapter = FakeHttpClientAdapter((request) {
      return jsonResponse(
        jsonEncode({'code': 409, 'msg': 'version_conflict', 'data': null}),
      );
    });
    final original = const CompanionProfile(
      id: 'p1',
      name: '原角色',
      summary: '',
      personality: '',
      systemPrompt: '',
      voice: '默认音色',
      capabilities: {},
      memoryEnabled: true,
      source: ProfileSource.custom,
    );
    final controller = ProfileController(initial: [original]);
    final updated = original.copyWith(name: '不应生效');

    final saved = await controller.saveRemote(
      ProfileRepository(testApiClient(adapter)),
      updated,
    );

    expect(saved, isFalse);
    expect(controller.profiles.single.name, '原角色');
    expect(controller.error, contains('version_conflict'));
  });
}
