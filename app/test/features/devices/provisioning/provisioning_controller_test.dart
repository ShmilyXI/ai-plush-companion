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
}
