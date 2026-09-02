import 'package:flutter/foundation.dart';

import '../data/device_repository.dart';
import '../../profiles/domain/profile_models.dart';
import '../provisioning/provisioning_models.dart';

class DeviceController extends ChangeNotifier {
  ProvisioningStep bindingStep = ProvisioningStep.idle;
  List<CompanionDevice> devices = const [];
  bool loading = false;
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

  Future<bool> load(DeviceRepository repository) async {
    loading = true;
    error = null;
    notifyListeners();
    try {
      final rows = await repository.list();
      devices = rows.map(CompanionDevice.fromMap).toList(growable: false);
      loading = false;
      notifyListeners();
      return true;
    } catch (value) {
      loading = false;
      error = value.toString();
      notifyListeners();
      return false;
    }
  }

  Future<bool> rename(
    DeviceRepository repository,
    String deviceId,
    String alias,
  ) => _perform(() async {
    await repository.update(deviceId, {'alias': alias});
    _replace(
      devices
          .where((device) => device.id == deviceId)
          .firstOrNull
          ?.copyWith(alias: alias),
    );
  });

  Future<bool> setProfile(
    DeviceRepository repository,
    String deviceId,
    String profileId,
  ) => _perform(() async {
    await repository.setProfile(deviceId, profileId);
    _replace(
      devices
          .where((device) => device.id == deviceId)
          .firstOrNull
          ?.copyWith(profileId: profileId),
    );
  });

  Future<bool> setCommand(
    DeviceRepository repository,
    String deviceId,
    String command,
    int value,
  ) => _perform(() async {
    await repository.command(deviceId, command, value);
    final current = devices
        .where((device) => device.id == deviceId)
        .firstOrNull;
    if (current == null) return;
    _replace(
      command == 'volume'
          ? current.copyWith(volume: value)
          : current.copyWith(brightness: value),
    );
  });

  Future<bool> unbind(DeviceRepository repository, String deviceId) =>
      _perform(() async {
        await repository.unbind(deviceId);
        devices = devices.where((device) => device.id != deviceId).toList();
      });

  Future<bool> _perform(Future<void> Function() operation) async {
    error = null;
    notifyListeners();
    try {
      await operation();
      notifyListeners();
      return true;
    } catch (value) {
      error = value.toString();
      notifyListeners();
      return false;
    }
  }

  void _replace(CompanionDevice? device) {
    if (device == null) return;
    devices = devices
        .map((item) => item.id == device.id ? device : item)
        .toList(growable: false);
  }
}
