## ADDED Requirements

### Requirement: Agent drafts are separate from published versions
The system SHALL allow an agent to have mutable draft configuration and SHALL create an immutable version when the draft is published.

#### Scenario: Publish an agent draft
- **WHEN** an authorized user publishes a valid agent draft
- **THEN** the system creates a new immutable version containing identity, prompt, model and voice bindings, memory policy, and Skill bindings

#### Scenario: Edit a published version
- **WHEN** a user attempts to change a published version
- **THEN** the system rejects in-place modification and requires a new draft

### Requirement: Devices follow the active agent version
The system SHALL resolve every bound device to its agent's active published version and SHALL NOT support per-device version pinning in the initial implementation.

#### Scenario: New connection after publish
- **WHEN** a device opens a new runtime connection after an agent version is activated
- **THEN** the runtime bundle uses the newly active version

#### Scenario: Active version changes during a session
- **WHEN** an agent's active version changes while a device session is already running
- **THEN** the session continues using the version captured at connection time and the next session uses the new active version

### Requirement: Published versions can be rolled back
The system SHALL activate a previous immutable version as the current active version without modifying the version's contents.

#### Scenario: Roll back an agent
- **WHEN** an authorized user activates a previous published version
- **THEN** new device connections resolve that version and the system records the activation as an auditable change

### Requirement: Publish validates runtime references
The system SHALL reject publication when required model references, Skill references, version references, or credential requirements are invalid.

#### Scenario: Publish with an unavailable model
- **WHEN** an agent draft references a disabled or missing required model resource
- **THEN** publication fails with a field-level reason and the previous active version remains unchanged
