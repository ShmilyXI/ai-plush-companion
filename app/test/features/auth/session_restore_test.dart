import 'package:ai_plush_companion/app.dart';
import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:ai_plush_companion/core/providers/core_providers.dart';
import 'package:ai_plush_companion/core/storage/secure_store.dart';
import 'package:ai_plush_companion/features/chat/data/conversation_repository.dart';
import 'package:ai_plush_companion/features/devices/data/device_repository.dart';
import 'package:ai_plush_companion/features/profiles/data/profile_repository.dart';
import 'package:ai_plush_companion/core/network/api_client.dart';
import 'package:ai_plush_companion/core/storage/preferences_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class _FailingSecureStore extends SecureStore {
  @override
  Future<StoredSession?> read() =>
      Future<StoredSession?>.error(StateError('storage unavailable'));
}

class _SessionSecureStore extends SecureStore {
  _SessionSecureStore(this.session);

  final StoredSession session;

  @override
  Future<StoredSession?> read() async => session;
}

class _EmptyProfileRepository extends ProfileRepository {
  _EmptyProfileRepository()
    : super(
        ApiClient(
          baseUrl: Uri.parse('https://test.invalid'),
          secureStore: SecureStore(),
        ),
      );

  @override
  Future<List<Map<String, dynamic>>> listProfiles() async => const [];
}

class _EmptyDeviceRepository extends DeviceRepository {
  _EmptyDeviceRepository()
    : super(
        ApiClient(
          baseUrl: Uri.parse('https://test.invalid'),
          secureStore: SecureStore(),
        ),
      );

  @override
  Future<List<Map<String, dynamic>>> list() async => const [];
}

class _EmptyConversationRepository extends ConversationRepository {
  _EmptyConversationRepository()
    : super(
        ApiClient(
          baseUrl: Uri.parse('https://test.invalid'),
          secureStore: SecureStore(),
        ),
      );

  @override
  Future<List<Map<String, dynamic>>> list() async => const [];
}

class _FixedPreferencesStore extends PreferencesStore {
  @override
  Future<bool> readAutoPlay() async => true;
}

void main() {
  testWidgets('falls back to the signed-out route when session restore fails', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appConfigProvider.overrideWithValue(
            AppConfig(
              apiBaseUrl: Uri.parse('https://api.example.test'),
              isDemo: false,
            ),
          ),
          secureStoreProvider.overrideWithValue(_FailingSecureStore()),
        ],
        child: const CompanionApp(initialLocation: '/chat'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('欢迎回来'), findsOneWidget);
  });

  testWidgets(
    'restores a valid session and sends an empty account to onboarding',
    (tester) async {
      final session = StoredSession(
        accessToken: 'access',
        refreshToken: 'refresh',
        accessExpiresAt: DateTime.now().add(const Duration(hours: 1)),
        refreshExpiresAt: DateTime.now().add(const Duration(days: 1)),
        userId: '7',
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            appConfigProvider.overrideWithValue(
              AppConfig(
                apiBaseUrl: Uri.parse('https://api.example.test'),
                isDemo: false,
              ),
            ),
            secureStoreProvider.overrideWithValue(_SessionSecureStore(session)),
            profileRepositoryProvider.overrideWithValue(
              _EmptyProfileRepository(),
            ),
            deviceRepositoryProvider.overrideWithValue(
              _EmptyDeviceRepository(),
            ),
            conversationRepositoryProvider.overrideWithValue(
              _EmptyConversationRepository(),
            ),
            preferencesStoreProvider.overrideWithValue(
              _FixedPreferencesStore(),
            ),
          ],
          child: const CompanionApp(initialLocation: '/chat'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('先选一个陪伴角色'), findsOneWidget);
    },
  );
}
