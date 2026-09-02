import 'dart:async';

import 'package:ai_plush_companion/features/devices/provisioning/device_portal_delegate.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:webview_flutter/webview_flutter.dart';

void main() {
  test('accepts only the local portal and reports exact done page', () async {
    var succeeded = 0;
    final delegate = DevicePortalDelegate(onPortalSucceeded: () => succeeded++);
    expect(
      delegate.handleNavigation(
        const NavigationRequest(
          url: 'http://192.168.4.1/index.html',
          isMainFrame: true,
        ),
      ),
      NavigationDecision.navigate,
    );
    expect(
      delegate.handleNavigation(
        const NavigationRequest(url: 'https://example.com/', isMainFrame: true),
      ),
      NavigationDecision.prevent,
    );
    expect(
      delegate.handleNavigation(
        const NavigationRequest(
          url: 'http://192.168.4.1/done.html',
          isMainFrame: true,
        ),
      ),
      NavigationDecision.navigate,
    );
    await delegate.finish();
    expect(succeeded, 1);
  });

  test('ignores a done page loaded in a subframe', () async {
    var succeeded = 0;
    final delegate = DevicePortalDelegate(onPortalSucceeded: () => succeeded++);
    expect(
      delegate.handleNavigation(
        const NavigationRequest(
          url: 'http://192.168.4.1/done.html',
          isMainFrame: false,
        ),
      ),
      NavigationDecision.navigate,
    );
    await delegate.finish();
    expect(succeeded, 0);
  });

  test('bounds the portal exit request before advancing the flow', () async {
    final never = Completer<void>();
    var succeeded = 0;
    final delegate = DevicePortalDelegate(
      onPortalSucceeded: () => succeeded++,
      onExitRequested: () => never.future,
      exitTimeout: const Duration(milliseconds: 10),
    );
    delegate.handleNavigation(
      const NavigationRequest(
        url: 'http://192.168.4.1/done.html',
        isMainFrame: true,
      ),
    );

    await expectLater(
      delegate.finish().timeout(const Duration(milliseconds: 100)),
      completes,
    );
    expect(succeeded, 1);
  });
}
