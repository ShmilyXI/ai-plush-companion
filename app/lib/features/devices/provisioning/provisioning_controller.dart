import 'dart:async';

import 'package:flutter/foundation.dart';

import 'provisioning_models.dart';

typedef ProvisioningDeviceLoader =
    Future<List<Map<String, dynamic>>> Function();
typedef ProvisioningDelay = Future<void> Function(Duration duration);
typedef ProvisioningClock = DateTime Function();

class ProvisioningController extends ChangeNotifier {
  ProvisioningController({ProvisioningClock? now, ProvisioningDelay? delay})
    : _now = now ?? DateTime.now,
      _delay = delay ?? Future<void>.delayed;

  final ProvisioningClock _now;
  final ProvisioningDelay _delay;
  ProvisioningState state = const ProvisioningState();
  int _epoch = 0;
  bool _disposed = false;
  bool _bindingInFlight = false;

  int begin({Uri? portalUri, String? profileId}) {
    if (_disposed) return state.attemptId;
    _epoch++;
    final attempt = state.attemptId + 1;
    state = ProvisioningState(
      step: ProvisioningStep.hotspotInstructions,
      devicePortalUri: portalUri ?? Uri.parse('http://192.168.4.1/'),
      startedAt: _now(),
      selectedProfileId: profileId,
      attemptId: attempt,
    );
    notifyListeners();
    return attempt;
  }

  void openPortal({int? attemptId}) {
    if (!_isAttempt(attemptId)) return;
    _set(ProvisioningStep.portal);
  }

  void portalSucceeded({int? attemptId}) {
    if (!_isAttempt(attemptId)) return;
    _set(ProvisioningStep.waitingForDevice);
  }

  void readyForActivation({int? attemptId}) {
    if (!_isAttempt(attemptId)) return;
    _set(ProvisioningStep.activationCode);
  }

  void setActivationCode(String code) {
    if (_disposed) return;
    state = state.copyWith(activationCodeDraft: code, lastError: null);
    notifyListeners();
  }

  bool beginBinding(String? profileId) {
    if (_disposed) return false;
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

  Future<bool> bind({
    required Future<Object?> Function(String activationCode, String? profileId)
    operation,
    String? profileId,
    ProvisioningDeviceLoader? pollDevices,
    Iterable<Map<String, dynamic>> knownDevices = const [],
    String? expectedDeviceId,
    String? expectedMacAddress,
    Duration timeout = const Duration(minutes: 5),
    Duration initialDelay = const Duration(seconds: 1),
    Duration maxDelay = const Duration(seconds: 30),
  }) {
    return bindWithResult(
      operation: operation,
      profileId: profileId,
      pollDevices: pollDevices,
      knownDevices: knownDevices,
      expectedDeviceId: expectedDeviceId,
      expectedMacAddress: expectedMacAddress,
      timeout: timeout,
      initialDelay: initialDelay,
      maxDelay: maxDelay,
    );
  }

  /// Binds the code and, when a loader is supplied, waits for the newly-owned
  /// device to appear in the authenticated device list. The optional result
  /// is intentionally untyped because older manager-api versions return a
  /// null data envelope while newer ones may return device metadata.
  Future<bool> bindWithResult({
    required Future<Object?> Function(String activationCode, String? profileId)
    operation,
    String? profileId,
    ProvisioningDeviceLoader? pollDevices,
    Iterable<Map<String, dynamic>> knownDevices = const [],
    String? expectedDeviceId,
    String? expectedMacAddress,
    Duration timeout = const Duration(minutes: 5),
    Duration initialDelay = const Duration(seconds: 1),
    Duration maxDelay = const Duration(seconds: 30),
  }) async {
    if (_bindingInFlight) return false;
    if (!beginBinding(profileId)) return false;
    _bindingInFlight = true;
    final attempt = state.attemptId;
    final epoch = _epoch;
    try {
      final result = await operation(
        state.activationCodeDraft,
        state.selectedProfileId,
      );
      if (!_isCurrent(attempt, epoch)) return false;
      final hint = _deviceHint(result);
      final deviceId = expectedDeviceId ?? hint.id;
      final deviceMac = expectedMacAddress ?? hint.macAddress;
      final matched = pollDevices == null
          ? null
          : await _pollForDevice(
              pollDevices,
              knownDevices: knownDevices,
              expectedDeviceId: deviceId,
              expectedMacAddress: deviceMac,
              timeout: timeout,
              initialDelay: initialDelay,
              maxDelay: maxDelay,
              attempt: attempt,
              epoch: epoch,
            );
      if (!_isCurrent(attempt, epoch)) return false;
      final matchedId = matched == null
          ? null
          : _value(matched, const ['id', 'deviceId', 'device_id']);
      final matchedMac = matched == null
          ? null
          : _value(matched, const ['macAddress', 'mac_address', 'mac']);
      state = state.copyWith(
        boundDeviceId: matchedId ?? deviceId,
        boundDeviceMac: matchedMac ?? deviceMac,
      );
      succeed();
      return true;
    } on _ProvisioningCancelled {
      return false;
    } on TimeoutException {
      _bindingFailed(attempt, epoch, '设备上线超时，请确认设备已连接网络后重试');
      return false;
    } catch (error) {
      _bindingFailed(attempt, epoch, error.toString());
      return false;
    } finally {
      _bindingInFlight = false;
    }
  }

  Future<Map<String, dynamic>?> _pollForDevice(
    ProvisioningDeviceLoader loader, {
    required Iterable<Map<String, dynamic>> knownDevices,
    required String? expectedDeviceId,
    required String? expectedMacAddress,
    required Duration timeout,
    required Duration initialDelay,
    required Duration maxDelay,
    required int attempt,
    required int epoch,
  }) async {
    final knownIds = knownDevices
        .map((device) => _value(device, const ['id', 'deviceId', 'device_id']))
        .whereType<String>()
        .toSet();
    final knownMacs = knownDevices
        .map(
          (device) => _normalizeMac(
            _value(device, const ['macAddress', 'mac_address', 'mac']),
          ),
        )
        .whereType<String>()
        .toSet();
    final deadline = _now().add(timeout);
    final maxAttempts = _maximumPollAttempts(timeout, initialDelay, maxDelay);
    var waitFor = initialDelay;
    Object? lastError;

    for (var index = 0; index < maxAttempts; index++) {
      if (!_isCurrent(attempt, epoch)) throw const _ProvisioningCancelled();
      List<Map<String, dynamic>> devices;
      try {
        devices = await loader();
      } catch (error) {
        lastError = error;
        devices = const [];
      }
      if (!_isCurrent(attempt, epoch)) {
        throw const _ProvisioningCancelled();
      }
      state = state.copyWith(pollAttempts: state.pollAttempts + 1);
      notifyListeners();
      final match = _matchDevice(
        devices,
        knownIds: knownIds,
        knownMacs: knownMacs,
        expectedDeviceId: expectedDeviceId,
        expectedMacAddress: expectedMacAddress,
      );
      if (match != null) return match;

      final remaining = deadline.difference(_now());
      if (remaining <= Duration.zero || index + 1 >= maxAttempts) break;
      final wait = waitFor <= remaining ? waitFor : remaining;
      if (wait > Duration.zero) await _delay(wait);
      final doubledMs = waitFor.inMilliseconds <= 0
          ? 1
          : waitFor.inMilliseconds * 2;
      waitFor = Duration(
        milliseconds: doubledMs.clamp(1, maxDelay.inMilliseconds),
      );
    }
    if (lastError != null) {
      throw TimeoutException('设备上线超时: $lastError');
    }
    throw TimeoutException('设备上线超时');
  }

  Map<String, dynamic>? _matchDevice(
    List<Map<String, dynamic>> devices, {
    required Set<String> knownIds,
    required Set<String> knownMacs,
    required String? expectedDeviceId,
    required String? expectedMacAddress,
  }) {
    final expectedMac = _normalizeMac(expectedMacAddress);
    if (expectedDeviceId != null || expectedMac != null) {
      for (final device in devices) {
        if (!_isOnline(device)) continue;
        final id = _value(device, const ['id', 'deviceId', 'device_id']);
        final mac = _normalizeMac(
          _value(device, const ['macAddress', 'mac_address', 'mac']),
        );
        if ((expectedDeviceId != null && id == expectedDeviceId) ||
            (expectedMac != null && mac == expectedMac)) {
          return device;
        }
      }
      return null;
    }

    // A bind response from older servers has no device hint. In that case a
    // single new identity is safe to select; multiple candidates stay
    // unresolved instead of silently binding the first list item.
    final candidates = devices.where((device) {
      if (!_isOnline(device)) return false;
      final id = _value(device, const ['id', 'deviceId', 'device_id']);
      final mac = _normalizeMac(
        _value(device, const ['macAddress', 'mac_address', 'mac']),
      );
      return (id != null && !knownIds.contains(id)) ||
          (mac != null && !knownMacs.contains(mac));
    }).toList();
    return candidates.length == 1 ? candidates.single : null;
  }

  bool _isOnline(Map<String, dynamic> device) {
    final raw = device['online'];
    if (raw == null) return true;
    if (raw is bool) return raw;
    if (raw is num) return raw != 0;
    final value = raw.toString().trim().toLowerCase();
    return value == 'true' || value == '1' || value == 'online';
  }

  int _maximumPollAttempts(
    Duration timeout,
    Duration initialDelay,
    Duration maxDelay,
  ) {
    final totalMs = timeout.inMilliseconds.clamp(1, 30 * 60 * 1000);
    var delayMs = initialDelay.inMilliseconds;
    final capMs = maxDelay.inMilliseconds;
    if (delayMs <= 0) return 1;
    var elapsed = 0;
    var attempts = 1;
    while (elapsed < totalMs && attempts < 1024) {
      elapsed += delayMs;
      attempts++;
      delayMs = (delayMs * 2).clamp(1, capMs <= 0 ? delayMs : capMs);
    }
    return attempts;
  }

  _DeviceHint _deviceHint(Object? result) {
    if (result is! Map) return const _DeviceHint();
    final map = Map<String, dynamic>.from(result);
    final nested = map['device'] is Map
        ? Map<String, dynamic>.from(map['device'] as Map)
        : map['data'] is Map
        ? Map<String, dynamic>.from(map['data'] as Map)
        : map;
    return _DeviceHint(
      id: _value(nested, const ['id', 'deviceId', 'device_id']),
      macAddress: _value(nested, const ['macAddress', 'mac_address', 'mac']),
    );
  }

  String? _value(Map<String, dynamic> map, List<String> keys) {
    for (final key in keys) {
      final value = map[key]?.toString().trim();
      if (value != null && value.isNotEmpty) return value;
    }
    return null;
  }

  String? _normalizeMac(String? value) {
    if (value == null || value.trim().isEmpty) return null;
    final normalized = value
        .replaceAll(RegExp(r'[^0-9a-fA-F]'), '')
        .toUpperCase();
    return normalized.isEmpty ? null : normalized;
  }

  bool _isCurrent(int attempt, int epoch) =>
      !_disposed && state.attemptId == attempt && _epoch == epoch;

  bool _isAttempt(int? attemptId) =>
      !_disposed && (attemptId == null || attemptId == state.attemptId);

  void _bindingFailed(int attempt, int epoch, String message) {
    if (!_isCurrent(attempt, epoch)) return;
    state = state.copyWith(
      step: ProvisioningStep.activationCode,
      lastError: message,
    );
    notifyListeners();
  }

  void succeed() {
    _set(ProvisioningStep.success);
  }

  void fail(String message) {
    if (_disposed) return;
    state = state.copyWith(step: ProvisioningStep.failed, lastError: message);
    notifyListeners();
  }

  void cancel() {
    _epoch++;
    _set(ProvisioningStep.cancelled);
  }

  void resume() {
    if (state.step == ProvisioningStep.portal ||
        state.step == ProvisioningStep.waitingForDevice) {
      notifyListeners();
    }
  }

  void _set(ProvisioningStep step) {
    if (_disposed) return;
    state = state.copyWith(step: step, lastError: null);
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _epoch++;
    super.dispose();
  }
}

class _DeviceHint {
  const _DeviceHint({this.id, this.macAddress});
  final String? id;
  final String? macAddress;
}

class _ProvisioningCancelled implements Exception {
  const _ProvisioningCancelled();
}
