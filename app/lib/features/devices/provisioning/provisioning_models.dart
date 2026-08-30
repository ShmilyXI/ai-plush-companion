enum ProvisioningStep {
  idle,
  hotspotInstructions,
  portal,
  waitingForDevice,
  activationCode,
  binding,
  success,
  failed,
  cancelled,
}

class ProvisioningState {
  const ProvisioningState({
    this.step = ProvisioningStep.idle,
    this.devicePortalUri,
    this.startedAt,
    this.lastError,
    this.selectedProfileId,
    this.activationCodeDraft = '',
    this.attemptId = 0,
  });
  final ProvisioningStep step;
  final Uri? devicePortalUri;
  final DateTime? startedAt;
  final String? lastError;
  final String? selectedProfileId;
  final String activationCodeDraft;
  final int attemptId;

  ProvisioningState copyWith({
    ProvisioningStep? step,
    Uri? devicePortalUri,
    DateTime? startedAt,
    String? lastError,
    String? selectedProfileId,
    String? activationCodeDraft,
    int? attemptId,
  }) => ProvisioningState(
    step: step ?? this.step,
    devicePortalUri: devicePortalUri ?? this.devicePortalUri,
    startedAt: startedAt ?? this.startedAt,
    lastError: lastError,
    selectedProfileId: selectedProfileId ?? this.selectedProfileId,
    activationCodeDraft: activationCodeDraft ?? this.activationCodeDraft,
    attemptId: attemptId ?? this.attemptId,
  );
}
