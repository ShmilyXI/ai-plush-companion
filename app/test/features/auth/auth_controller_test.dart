import 'dart:async';

import 'package:ai_plush_companion/features/auth/application/auth_controller.dart';
import 'package:ai_plush_companion/features/auth/domain/auth_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('validates phone and email contacts before sending a code', () {
    expect(validateContact(ContactChannel.phone, '13800138000'), isNull);
    expect(validateContact(ContactChannel.phone, 'abc'), isNotNull);
    expect(validateContact(ContactChannel.email, 'user@example.com'), isNull);
    expect(validateContact(ContactChannel.email, 'invalid'), isNotNull);
  });

  test('matches the server password strength rule before registration', () {
    expect(validateNewPassword('abc123'), isNotNull);
    expect(validateNewPassword('Abc123'), isNull);
  });

  test(
    'ignores a stale verification response after a newer request starts',
    () async {
      final controller = AuthController();
      final first = Completer<String>();
      final second = Completer<String>();

      final firstRequest = controller.sendCode(() => first.future);
      final secondRequest = controller.sendCode(() => second.future);
      second.complete('challenge-2');
      await secondRequest;
      first.complete('challenge-1');
      await firstRequest;

      expect(controller.challengeId, 'challenge-2');
      expect(controller.status, AuthStatus.codeSent);
      controller.dispose();
    },
  );

  test('starts a resend countdown after a successful code request', () async {
    final controller = AuthController();

    await controller.sendCode(() async => 'challenge');

    expect(controller.retryAfterSeconds, 60);
    controller.dispose();
  });
}
