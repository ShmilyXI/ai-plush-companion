import 'package:flutter/foundation.dart';

import 'provisioning_models.dart';

class ProvisioningController extends ChangeNotifier {
  ProvisioningState state = const ProvisioningState();

  int begin({Uri? portalUri, String? profileId}) {
    final attempt = state.attemptId + 1;
    state = ProvisioningState(
      step: ProvisioningStep.hotspotInstructions,
      devicePortalUri: portalUri ?? Uri.parse('http://192.168.4.1/'),
      startedAt: DateTime.now(),
      selectedProfileId: profileId,
      attemptId: attempt,
    );
    notifyListeners();
    return attempt;
  }

  void openPortal() {
    _set(ProvisioningStep.portal);
  }

  void portalSucceeded() {
    _set(ProvisioningStep.waitingForDevice);
  }

  void readyForActivation() {
    _set(ProvisioningStep.activationCode);
  }

  void setActivationCode(String code) {
    state = state.copyWith(activationCodeDraft: code, lastError: null);
    notifyListeners();
  }

  bool beginBinding(String? profileId) {
    if (!RegExp(r'^\d{6}$').hasMatch(state.activationCodeDraft)) {
      state = state.copyWith(lastError: '请输入六位激活码');
      notifyListeners();
      return false;
    }
    state = state.copyWith(
      step: ProvisioningStep.binding,
      selectedProfileId: profileId,
    );
    notifyListeners();
    return true;
  }

  void succeed() {
    _set(ProvisioningStep.success);
  }

  void fail(String message) {
    state = state.copyWith(step: ProvisioningStep.failed, lastError: message);
    notifyListeners();
  }

  void cancel() {
    _set(ProvisioningStep.cancelled);
  }

  void resume() {
    if (state.step == ProvisioningStep.portal ||
        state.step == ProvisioningStep.waitingForDevice) {
      notifyListeners();
    }
  }

  void _set(ProvisioningStep step) {
    state = state.copyWith(step: step, lastError: null);
    notifyListeners();
  }
}
