import 'dart:convert';

import 'package:ai_plush_companion/features/account/data/account_repository.dart';
import 'package:ai_plush_companion/features/auth/domain/auth_models.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test(
    'loads the authenticated account summary without token fields',
    () async {
      String? requestedPath;
      final adapter = FakeHttpClientAdapter((request) {
        requestedPath = request.path;
        return jsonResponse(
          jsonEncode({
            'code': 0,
            'msg': 'success',
            'data': {
              'user': {
                'id': 7,
                'displayName': '拾光用户',
                'username': 'user@example.com',
                'avatarUrl': null,
              },
              'verifiedChannels': ['email'],
            },
          }),
        );
      });

      final account = await AccountRepository(testApiClient(adapter)).get();

      expect(account.user.displayName, '拾光用户');
      expect(account.verifiedChannels, [ContactChannel.email]);
      expect(requestedPath, '/app/account');
    },
  );
}
