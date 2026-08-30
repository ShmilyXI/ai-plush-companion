import 'package:ai_plush_companion/app.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('renders the health route', (tester) async {
    await tester.pumpWidget(const CompanionApp());
    await tester.pumpAndSettle();

    expect(find.text('AI 陪伴'), findsOneWidget);
    expect(find.text('服务正常'), findsOneWidget);
  });
}
