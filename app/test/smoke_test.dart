import 'package:ai_plush_companion/app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  testWidgets('renders the health route', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: CompanionApp(initialLocation: '/health')),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('AI 陪伴'), findsOneWidget);
  });
}
