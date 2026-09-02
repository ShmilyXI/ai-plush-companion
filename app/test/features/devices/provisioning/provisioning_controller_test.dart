import 'dart:async';

import 'package:ai_plush_companion/features/devices/provisioning/provisioning_controller.dart';
import 'package:ai_plush_companion/features/devices/provisioning/provisioning_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('advances through the resumable provisioning steps', () {
    final controller = ProvisioningController();
    controller.begin();
    expect(controller.state.step, ProvisioningStep.hotspotInstructions);
    controller.openPortal();
    controller.portalSucceeded();
    controller.readyForActivation();
    controller.setActivationCode('123456');
    expect(controller.beginBinding('profile-a'), isTrue);
    expect(controller.state.step, ProvisioningStep.binding);
    controller.succeed();
    expect(controller.state.step, ProvisioningStep.success);
  });

  test('rejects an invalid activation code without advancing', () {
    final controller = ProvisioningController();
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('12');
    expect(controller.beginBinding(null), isFalse);
    expect(controller.state.step, ProvisioningStep.activationCode);
    expect(controller.state.lastError, isNotNull);
  });

  test('returns to the code step when cloud binding fails', () async {
    final controller = ProvisioningController();
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('123456');
    final result = await controller.bind(
      operation: (_, __) async => throw StateError('offline'),
      profileId: 'profile-a',
    );
    expect(result, isFalse);
    expect(controller.state.step, ProvisioningStep.activationCode);
    expect(controller.state.lastError, contains('offline'));
  });

  test(
    'polls owned devices after binding and matches a newly owned device',
    () async {
      var now = DateTime(2026, 1, 1);
      final delays = <Duration>[];
      final pages = <List<Map<String, dynamic>>>[
        [
          {'id': 'old-device', 'macAddress': 'AA:AA'},
        ],
        [
          {'id': 'old-device', 'macAddress': 'AA:AA'},
          {'id': 'new-device', 'macAddress': 'BB:BB'},
        ],
      ];
      final controller = ProvisioningController(
        now: () => now,
        delay: (duration) async {
          delays.add(duration);
          now = now.add(duration);
        },
      );
      controller.begin();
      controller.readyForActivation();
      controller.setActivationCode('123456');

      final result = await controller.bind(
        operation: (_, __) async => null,
        knownDevices: const [
          {'id': 'old-device', 'macAddress': 'AA:AA'},
        ],
        pollDevices: () async => pages.removeAt(0),
        timeout: const Duration(seconds: 20),
        initialDelay: const Duration(seconds: 1),
        maxDelay: const Duration(seconds: 30),
      );

      expect(result, isTrue);
      expect(controller.state.step, ProvisioningStep.success);
      expect(controller.state.boundDeviceId, 'new-device');
      expect(delays, [const Duration(seconds: 1)]);
    },
  );

  test('does not select an unrelated first device and times out', () async {
    var now = DateTime(2026, 1, 1);
    final controller = ProvisioningController(
      now: () => now,
      delay: (duration) async => now = now.add(duration),
    );
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('123456');

    final result = await controller.bind(
      operation: (_, __) async => null,
      knownDevices: const [
        {'id': 'old-device', 'macAddress': 'AA:AA'},
      ],
      pollDevices: () async => [
        {'id': 'old-device', 'macAddress': 'AA:AA'},
        {'id': 'other-device', 'macAddress': 'CC:CC'},
        {'id': 'another-device', 'macAddress': 'DD:DD'},
      ],
      timeout: const Duration(seconds: 2),
      initialDelay: const Duration(seconds: 1),
    );

    expect(result, isFalse);
    expect(controller.state.step, ProvisioningStep.activationCode);
    expect(controller.state.lastError, contains('设备上线超时'));
    expect(controller.state.boundDeviceId, isNull);
  });

  test(
    'uses an explicit device hint even when several devices appear',
    () async {
      final controller = ProvisioningController();
      controller.begin();
      controller.readyForActivation();
      controller.setActivationCode('123456');

      final result = await controller.bind(
        operation: (_, __) async => null,
        expectedDeviceId: 'target-device',
        pollDevices: () async => [
          {'id': 'other-device', 'macAddress': 'CC:CC'},
          {'id': 'target-device', 'macAddress': 'BB:BB'},
        ],
        timeout: const Duration(seconds: 1),
      );

      expect(result, isTrue);
      expect(controller.state.boundDeviceId, 'target-device');
    },
  );

  test('accepts optional device metadata from a newer bind endpoint', () async {
    final controller = ProvisioningController();
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('123456');
    final result = await controller.bind(
      operation: (_, __) async => {'deviceId': 'bound-device'},
      pollDevices: () async => [
        {'id': 'bound-device'},
      ],
      timeout: const Duration(seconds: 1),
    );
    expect(result, isTrue);
    expect(controller.state.boundDeviceId, 'bound-device');
  });

  test('keeps polling until the matched device reports online', () async {
    var now = DateTime(2026, 1, 1);
    final pages = <List<Map<String, dynamic>>>[
      [
        {'id': 'new-device', 'online': false},
      ],
      [
        {'id': 'new-device', 'online': true},
      ],
    ];
    final controller = ProvisioningController(
      now: () => now,
      delay: (duration) async => now = now.add(duration),
    );
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('123456');

    final result = await controller.bind(
      operation: (_, __) async => {'deviceId': 'new-device'},
      pollDevices: () async => pages.removeAt(0),
      timeout: const Duration(seconds: 10),
    );

    expect(result, isTrue);
    expect(controller.state.pollAttempts, 2);
  });

  test('ignores a poll result from an older provisioning attempt', () async {
    final pending = Completer<List<Map<String, dynamic>>>();
    final controller = ProvisioningController(delay: (_) async {});
    controller.begin();
    controller.readyForActivation();
    controller.setActivationCode('123456');
    final binding = controller.bind(
      operation: (_, __) async => null,
      knownDevices: const [
        {'id': 'old-device'},
      ],
      pollDevices: () => pending.future,
      timeout: const Duration(seconds: 1),
    );
    await Future<void>.delayed(Duration.zero);
    controller.begin();
    pending.complete([
      {'id': 'new-device'},
    ]);

    expect(await binding, isFalse);
    expect(controller.state.step, ProvisioningStep.hotspotInstructions);
  });

  test('ignores portal callbacks from an older provisioning attempt', () {
    final controller = ProvisioningController();
    final firstAttempt = controller.begin();
    controller.begin();
    controller.portalSucceeded(attemptId: firstAttempt);

    expect(controller.state.step, ProvisioningStep.hotspotInstructions);
  });

  test(
    'does not start a second bind while the first request is in flight',
    () async {
      final operationGate = Completer<void>();
      final controller = ProvisioningController();
      controller.begin();
      controller.readyForActivation();
      controller.setActivationCode('123456');
      var operations = 0;
      final first = controller.bind(
        operation: (_, __) async {
          operations++;
          await operationGate.future;
          return null;
        },
      );
      await Future<void>.delayed(Duration.zero);
      final second = await controller.bind(
        operation: (_, __) async => operations++,
      );
      expect(second, isFalse);
      operationGate.complete();
      expect(await first, isTrue);
      expect(operations, 1);
    },
  );
}
