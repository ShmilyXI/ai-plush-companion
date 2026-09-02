import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/providers/core_providers.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:ai_plush_companion/features/auth/data/auth_repository.dart';
import 'package:ai_plush_companion/features/auth/domain/auth_models.dart';
import 'package:ai_plush_companion/features/auth/presentation/login_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeAuthRepository extends AuthRepository {
  _FakeAuthRepository()
    : super(
        ApiClient(
          baseUrl: Uri.parse('https://test.invalid'),
          secureStore: SecureStore(),
        ),
        SecureStore(),
      );

  ContactChannel? requestedChannel;
  CodePurpose? requestedPurpose;

  @override
  Future<AuthCodeChallenge> requestCode({
    required ContactChannel channel,
    required String value,
    required CodePurpose purpose,
    String? countryCode,
  }) async {
    requestedChannel = channel;
    requestedPurpose = purpose;
    return AuthCodeChallenge(
      challengeId: 'challenge',
      expiresAt: DateTime.now().add(const Duration(minutes: 5)),
      retryAfterSeconds: 1,
    );
  }
}

void main() {
  testWidgets('code login requests a backend verification code', (
    tester,
  ) async {
    final fake = _FakeAuthRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appConfigProvider.overrideWithValue(
            AppConfig(
              apiBaseUrl: Uri.parse('https://test.invalid'),
              isDemo: false,
            ),
          ),
          authRepositoryProvider.overrideWithValue(fake),
        ],
        child: const MaterialApp(home: LoginPage()),
      ),
    );

    await tester.enterText(find.byType(TextField).first, '13800138000');
    await tester.tap(find.text('验证码登录'));
    await tester.pump();
    await tester.tap(find.text('获取验证码'));
    await tester.pumpAndSettle();

    expect(fake.requestedChannel, ContactChannel.phone);
    expect(fake.requestedPurpose, CodePurpose.login);
    expect(find.text('验证码已发送，请查收'), findsOneWidget);
  });
}
