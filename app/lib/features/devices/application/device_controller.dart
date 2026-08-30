import 'package:flutter/foundation.dart';

import '../provisioning/provisioning_models.dart';

class DeviceController extends ChangeNotifier {
  ProvisioningStep bindingStep = ProvisioningStep.idle;
  String? error;

  bool validateActivationCode(String value) {
    final valid = RegExp(r'^\d{6}$').hasMatch(value);
    if (!valid) error = '请输入六位激活码';
    notifyListeners();
    return valid;
  }

  Future<bool> bind(
    Future<void> Function() operation,
    String activationCode,
  ) async {
    if (!validateActivationCode(activationCode)) return false;
    bindingStep = ProvisioningStep.binding;
    error = null;
    notifyListeners();
    try {
      await operation();
      bindingStep = ProvisioningStep.success;
      notifyListeners();
      return true;
    } catch (value) {
      bindingStep = ProvisioningStep.activationCode;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }
}
