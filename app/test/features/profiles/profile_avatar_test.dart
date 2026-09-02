import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:ai_plush_companion/core/providers/core_providers.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:ai_plush_companion/features/profiles/domain/profile_models.dart';
import 'package:ai_plush_companion/features/profiles/presentation/profile_selector_drawer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _FixedSecureStore extends SecureStore {
  _FixedSecureStore(this.session);

  final StoredSession session;

  @override
  Future<StoredSession?> read() async => session;
}

void main() {
  testWidgets(
    'resolves owner-scoped relative avatar URLs against the API base',
    (tester) async {
      const profile = CompanionProfile(
        id: 'profile-a',
        name: '露娜',
        summary: '',
        personality: '',
        systemPrompt: '',
        voice: '默认音色',
        capabilities: {},
        memoryEnabled: true,
        source: ProfileSource.custom,
        avatarUrl: '/app/assets/avatars/profile-a/checksum',
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            appConfigProvider.overrideWithValue(
              AppConfig(apiBaseUrl: Uri.parse('https://api.example/xiaozhi')),
            ),
            secureStoreProvider.overrideWithValue(
              _FixedSecureStore(
                StoredSession(
                  accessToken: 'access-token',
                  refreshToken: 'refresh-token',
                  accessExpiresAt: DateTime(2099),
                  refreshExpiresAt: DateTime(2099, 1, 2),
                  userId: '7',
                ),
              ),
            ),
          ],
          child: const MaterialApp(home: ProfileAvatar(profile: profile)),
        ),
      );
      await tester.pump();

      final image = tester.widget<Image>(find.byType(Image));
      final provider = image.image as NetworkImage;
      expect(
        provider.url,
        'https://api.example/xiaozhi/app/assets/avatars/profile-a/checksum',
      );
      expect(provider.headers?['Authorization'], 'Bearer access-token');
    },
  );

  testWidgets('does not load unsafe avatar URL schemes', (tester) async {
    const profile = CompanionProfile(
      id: 'profile-a',
      name: '露娜',
      summary: '',
      personality: '',
      systemPrompt: '',
      voice: '默认音色',
      capabilities: {},
      memoryEnabled: true,
      source: ProfileSource.custom,
      avatarUrl: 'javascript:alert(1)',
    );
    await tester.pumpWidget(
      ProviderScope(
        child: const MaterialApp(home: ProfileAvatar(profile: profile)),
      ),
    );

    expect(find.byType(Image), findsNothing);
    expect(find.text('露'), findsOneWidget);
  });
}
