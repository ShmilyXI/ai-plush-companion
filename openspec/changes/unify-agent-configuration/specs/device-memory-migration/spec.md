## ADDED Requirements

### Requirement: Device memory remains isolated
The system SHALL store and resolve long-term memory using a namespace that includes the user, agent, and device, and SHALL NOT use a shared agent field as the source of truth for device memory summaries.

#### Scenario: Two devices use one agent
- **WHEN** two devices bound to the same agent save or query long-term memory
- **THEN** each device reads and writes only its own memory namespace

### Requirement: Users can migrate a complete memory library once
The system SHALL allow an authorized user to select a source device and target device for a one-time migration of the complete long-term memory library when both devices belong to the same user and agent.

#### Scenario: Start a migration
- **WHEN** the user selects eligible source and target devices
- **THEN** the system shows the source item count, target item count, migration mode, and a confirmation before starting the operation

#### Scenario: Reject an ineligible migration
- **WHEN** the source and target do not belong to the same user or agent
- **THEN** the system rejects the request before reading or writing memory data

### Requirement: Merge mode preserves both libraries
The system SHALL support a merge mode that keeps target memories, imports source memories, and skips duplicate logical content.

#### Scenario: Merge two libraries
- **WHEN** a migration runs in merge mode
- **THEN** unique source memories are added to the target, duplicate content is skipped, source memories remain unchanged, and the result reports imported and skipped counts

### Requirement: Overwrite mode replaces only the target library
The system SHALL support an overwrite mode that replaces the target device's long-term memory with the source library while preserving the source library.

#### Scenario: Overwrite a target library
- **WHEN** a migration runs in overwrite mode and completes successfully
- **THEN** the target contains the source library, the source remains unchanged, and the result records the replacement

#### Scenario: Overwrite cannot complete safely
- **WHEN** target backup, source export, or target import fails
- **THEN** the system reports failure, preserves or restores the target where the provider supports recovery, and never reports success for a partial replacement

### Requirement: Migration is auditable and retryable
The system SHALL record source, target, agent, mode, item counts, timestamps, operator, and outcome for every migration attempt, and SHALL make failed attempts retryable without deleting the source library.

#### Scenario: Inspect migration history
- **WHEN** an authorized user views migration history
- **THEN** the system shows the outcome and counts without exposing credentials or unrelated users' memory content
