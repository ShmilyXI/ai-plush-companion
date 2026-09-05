## Purpose

TBD: Define the canonical Zixuan product identity and the exceptions that must retain authentic external or historical names.

## Requirements

### Requirement: Product-owned identity uses Zixuan
The system SHALL use `zixuan` for every product-owned machine identifier and `紫萱` for every user-visible product identity.

#### Scenario: Scan product source
- **WHEN** the brand scanner examines tracked paths and file contents
- **THEN** no product-owned path, package, service, artifact, configuration name, or user-facing text contains the retired identity

### Requirement: External and historical identities remain authentic
The system SHALL preserve genuine upstream URLs and package names, vendor or SDK symbols, executed migration identities, and historical audit evidence through a reviewed allowlist.

#### Scenario: Scan an allowed upstream reference
- **WHEN** a retired-name match identifies an allowlisted upstream repository or immutable historical identity
- **THEN** the scanner reports it as permitted with its category and source location

#### Scenario: Scan an unclassified match
- **WHEN** a retired-name match is not covered by the allowlist
- **THEN** the release gate fails and identifies the path and line

### Requirement: Generated product metadata uses Zixuan
Build manifests, OpenAPI documents, package metadata, application titles, logs, and release archives SHALL use the new product identity.

#### Scenario: Inspect release artifacts
- **WHEN** the release artifacts are generated
- **THEN** product-owned artifact names and embedded metadata use `zixuan` or `紫萱` and contain no unapproved retired identity
