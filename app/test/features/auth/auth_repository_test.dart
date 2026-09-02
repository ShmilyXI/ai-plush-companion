import 'dart:convert';

import 'package:ai_plush_companion/features/auth/data/auth_repository.dart';
import 'package:ai_plush_companion/features/auth/domain/auth_models.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fake_http_client_adapter.dart';

void main() {
  test(
    'sends a code request with the backend purpose and channel names',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({
            'code': 0,
            'msg': 'success',
            'data': {
              'challengeId': 'challenge-1',
              'expiresAt': '2026-08-31T00:05:00Z',
              'retryAfterSeconds': 42,
            },
          }),
        );
      });
      final repository = AuthRepository(testApiClient(adapter), SecureStore());

      final result = await repository.sendCode(
        channel: ContactChannel.email,
        value: 'user@example.com',
        purpose: CodePurpose.register,
      );

      expect(result['challengeId'], 'challenge-1');
      expect(adapter.requests.single.path, '/app/auth/code');
      expect(adapter.requests.single.data, {
        'channel': 'email',
        'value': 'user@example.com',
        'purpose': 'register',
      });
    },
  );

  test(
    'normalizes a domestic phone before the server verifies later codes',
    () async {
      final adapter = FakeHttpClientAdapter((request) {
        return jsonResponse(
          jsonEncode({
            'code': 0,
            'msg': 'success',
            'data': {
              'challengeId': 'challenge-2',
              'expiresAt': '2026-08-31T00:05:00Z',
              'retryAfterSeconds': 60,
            },
          }),
        );
      });
      final repository = AuthRepository(testApiClient(adapter), SecureStore());

      await repository.sendCode(
        channel: ContactChannel.phone,
        value: '138 0013 8000',
        purpose: CodePurpose.reset,
        countryCode: '+86',
      );

      expect(adapter.requests.single.data, {
        'channel': 'phone',
        'value': '+8613800138000',
        'purpose': 'reset',
        'countryCode': '+86',
      });
    },
  );
}
