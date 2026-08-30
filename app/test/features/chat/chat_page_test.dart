import 'package:ai_plush_companion/app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  testWidgets('opens the session drawer from the left control', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: CompanionApp()));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('展开会话列表'));
    await tester.pumpAndSettle();
    expect(find.text('会话'), findsOneWidget);
    expect(find.text('开启新会话'), findsOneWidget);
  });

  testWidgets('opens the profile card drawer from the right control', (
    tester,
  ) async {
    await tester.pumpWidget(const ProviderScope(child: CompanionApp()));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('选择陪伴角色'));
    await tester.pumpAndSettle();
    expect(find.text('陪伴角色'), findsOneWidget);
    expect(find.text('露娜'), findsWidgets);
    expect(find.text('长期记忆'), findsWidgets);
  });
}
