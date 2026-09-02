import 'package:ai_plush_companion/features/devices/provisioning/provisioning_page.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';

void main() {
  testWidgets('shows the activation code step after the portal flow', (
    tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: ProvisioningPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('我已连接热点'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('打开配网页面'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('设备已完成'));
    await tester.pumpAndSettle();
    expect(find.text('输入六位激活码'), findsOneWidget);
  });
}
