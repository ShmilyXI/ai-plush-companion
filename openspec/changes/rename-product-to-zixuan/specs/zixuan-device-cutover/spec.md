## ADDED Requirements

### Requirement: Every supported board has a matching Zixuan release pair
Each supported product board SHALL have a board-specific application image and generated assets image built from its own `config.json`.

#### Scenario: Build a board release
- **WHEN** a supported board package is generated
- **THEN** its manifest records board type, firmware version, partition layout, application checksum, assets checksum, Zixuan endpoints, and factory wake word

### Requirement: Factory wake word is Zixuan
The product factory assets and device-visible default SHALL use `你好紫萱` while preserving the validated dynamic wake-word layout and slot behavior.

#### Scenario: Boot a freshly released device
- **WHEN** the device starts from the released application and assets pair
- **THEN** it reports the expected layout, loads the Zixuan factory wake word, and keeps other device capabilities available

### Requirement: Flashing preserves device identity data
The cutover process SHALL preserve eFuse identity and required NVS data and SHALL NOT write a blank-NVS merged binary to a bound device.

#### Scenario: Flash a bound device
- **WHEN** a device is upgraded for cutover
- **THEN** an NVS backup exists, only approved partitions are written, and the UUID, Wi-Fi recovery path, and hardware identity remain verifiable

### Requirement: Old devices require explicit reactivation
The released firmware SHALL reject obsolete service configuration and guide the device into the new activation and binding flow.

#### Scenario: Boot with obsolete service configuration
- **WHEN** a released device finds only retired endpoints or protocol settings
- **THEN** it remains in a recoverable activation state and does not enter a mixed-version conversation session

### Requirement: Real-device acceptance covers the complete path
The release SHALL verify startup, display, audio input/output, camera, buttons, capability reporting, activation, binding, text and audio conversation, interruption, MQTT reconnect, OTA, and return to service.

#### Scenario: Accept a released device
- **WHEN** a board completes the cutover checklist
- **THEN** the evidence links device identity, board, firmware and assets checksums, Agent version, protocol endpoints, session timeline, NVS preservation, and observed results
