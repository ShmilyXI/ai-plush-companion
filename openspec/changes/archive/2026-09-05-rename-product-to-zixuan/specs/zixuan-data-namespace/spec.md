## ADDED Requirements

### Requirement: Persistent product data uses Zixuan namespaces
The released system SHALL connect to `zixuan_esp32_server`, use `zixuan:` Redis keys, and use Zixuan object and log namespaces for new runtime data.

#### Scenario: Start a released service
- **WHEN** a service resolves its storage configuration
- **THEN** it rejects obsolete product database and namespace settings instead of falling back to them

### Requirement: Business ownership survives migration
The migration SHALL preserve users, devices, hardware identities, roles, Agent versions, capability bindings, conversations, memory ownership, and audit relationships.

#### Scenario: Run migration dry-run
- **WHEN** the migration runs against a snapshot
- **THEN** it reports source and target counts, key types, TTLs, digests, skipped transient data, conflicts, and failed invariants without changing the source

#### Scenario: Complete the migration
- **WHEN** all required counts, digests, and ownership invariants match
- **THEN** the migration marks the target ready and leaves the source snapshot recoverable

### Requirement: Immutable migration history is preserved
Executed Liquibase changesets and historical audit identities SHALL retain their original content and identifiers.

#### Scenario: Validate Liquibase after import
- **WHEN** the new database starts for the first time
- **THEN** Liquibase accepts the copied history without checksum changes and applies only new Zixuan migrations

### Requirement: Storage rollback is complete
Rollback SHALL restore the matching old database, Redis, object data, configuration, and service artifacts as one operation.

#### Scenario: Rehearse rollback
- **WHEN** the rollback command is exercised before release
- **THEN** the old stack starts against its old namespaces and passes management, public-session, MQTT, and memory health checks
