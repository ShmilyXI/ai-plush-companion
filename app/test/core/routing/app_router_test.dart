import 'package:ai_plush_companion/app.dart';
import 'package:ai_plush_companion/core/config/app_config.dart';
import 'package:ai_plush_companion/core/providers/companion_store.dart';
import 'package:ai_plush_companion/core/providers/core_providers.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  testWidgets('redirects a signed-in account with no profiles away from chat', (
    tester,
  ) async {
    final store = CompanionStore()..signIn();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appConfigProvider.overrideWithValue(
            AppConfig(
              apiBaseUrl: Uri.parse('https://test.invalid'),
              isDemo: true,
            ),
          ),
          companionStoreProvider.overrideWith((ref) => store),
        ],
        child: const CompanionApp(initialLocation: '/chat'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('先选一个陪伴角色'), findsOneWidget);
    expect(find.text('和陪伴角色聊聊'), findsNothing);
    expect(find.text('先进入聊天'), findsNothing);
  });
}
