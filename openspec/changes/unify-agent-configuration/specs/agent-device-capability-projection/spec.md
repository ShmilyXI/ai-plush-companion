## ADDED Requirements

### Requirement: Effective capabilities are projected from agent and device
The system SHALL build a device runtime capability bundle from the active agent version filtered by the device's reported hardware capabilities and device tools.

#### Scenario: Device supports all requested Skills
- **WHEN** a device reports every required capability for the active agent version
- **THEN** the effective bundle contains the agent's published Skills and their permitted tools and parameters

#### Scenario: Device lacks a required capability
- **WHEN** a device is bound to an agent whose published version contains a Skill requiring hardware the device does not report
- **THEN** the management projection marks that Skill unavailable with a reason and the runtime bundle excludes it

### Requirement: Device tools remain device-scoped
The system SHALL include only device tools reported by the current device and SHALL NOT expose tools from another device or another connection.

#### Scenario: Two devices use one agent
- **WHEN** two devices are bound to the same agent and report different tools
- **THEN** each runtime bundle contains only the intersection of that device's tools with the agent version's permitted tool references

### Requirement: Runtime bundle compatibility is preserved
The system SHALL preserve the existing Python-facing effective capability bundle contract or provide an explicitly versioned compatibility adapter while changing its source resolution to the active agent version.

#### Scenario: Python refreshes a device bundle
- **WHEN** the Python runtime requests the effective bundle for a device
- **THEN** the response contains the device identity, active configuration version, effective Skills, and executable tools in the format expected by the connection-isolated tool router

### Requirement: Legacy bindings are migrated safely
The system SHALL provide a compatibility migration from enabled legacy device Skill bindings to agent Skill bindings without silently dropping version policies, overrides, priorities, or audit history.

#### Scenario: Migrate a legacy binding
- **WHEN** a device has an enabled legacy Skill binding and its agent receives an initial published version
- **THEN** the binding is projected into the agent version with equivalent policy and an audit record identifies the migration source
