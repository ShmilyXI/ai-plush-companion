## 1. Establish the agent version model

- [x] 1.1 Add persistence for agent drafts, immutable published versions, active version selection, and version activation audit records.
- [x] 1.2 Define the version snapshot contract for identity, prompt, model and voice bindings, memory policy, and Skill bindings.
- [x] 1.3 Add manager-api validation for model references, Skill references, Skill versions, required credentials, and publish transitions.
- [x] 1.4 Backfill an initial published version for every existing agent and record the migration result.

## 2. Move Skill ownership to agents

- [x] 2.1 Add agent-to-Skill version bindings that preserve version mode, fixed version, overrides, trigger priority, and enabled state.
- [x] 2.2 Build a compatibility migration from enabled `ai_device_skill_mapping` rows into the owning agent's initial version.
- [x] 2.3 Add conflict detection and audit output for legacy device bindings that cannot be projected without user review.
- [x] 2.4 Change new management writes to update agent drafts and remove device-level Skill ownership from the primary console workflow.

## 3. Resolve effective device capabilities

- [x] 3.1 Update the effective capability bundle service to resolve the device's active agent version before applying device capability filters.
- [x] 3.2 Filter Skills and tools against reported hardware and device MCP tool snapshots while preserving connection and device isolation.
- [x] 3.3 Return unavailable Skill reasons in management projections and exclude unsupported tools from the Python runtime bundle.
- [x] 3.4 Preserve or version the Python-facing bundle contract and update Python cache invalidation when an agent version is published or activated.
- [x] 3.5 Add runtime tests for two devices sharing an agent with different hardware and tool sets.

## 4. Implement draft, publish, and rollback workflows

- [x] 4.1 Add agent draft read and update APIs that aggregate identity, models, voice, memory policy, Skills, and bound devices.
- [x] 4.2 Add publish and activate/rollback APIs with immutable version enforcement and audit records.
- [x] 4.3 Pin the resolved agent version for the lifetime of a Python connection and load the active version on the next connection.
- [x] 4.4 Add manager-api tests for invalid publication, rollback, active-session consistency, and all-devices-follow-active-version behavior.

## 5. Repair and migrate device memory ownership

- [x] 5.1 Stop using the shared agent `summaryMemory` field as the runtime source of truth for device memory and migrate summaries into device namespaces.
- [x] 5.2 Define Python provider export, import, deduplication, backup, and restore operations for the supported memory providers.
- [x] 5.3 Add an authorized one-time migration API restricted to source and target devices belonging to the same user and agent.
- [x] 5.4 Implement merge mode with logical-content deduplication and overwrite mode with target backup or provider-supported recovery.
- [x] 5.5 Record migration attempts, counts, modes, outcomes, and retry state without deleting source data.
- [x] 5.6 Add tests for isolation, merge, overwrite success, failed overwrite recovery, authorization, and retry behavior.

## 6. Consolidate the companion console

- [x] 6.1 Make the agent editor the primary workflow for identity, prompt, models, voice, memory policy, Skills, devices, and version publishing.
- [x] 6.2 Add draft and publish state, version activation, rollback, and unsaved-change handling to the agent editor.
- [x] 6.3 Add inline model credential configuration that uses reusable user-owned resources without exposing stored secrets.
- [x] 6.4 Replace device Skill editing with an effective-capability projection that explains unavailable Skills and hardware requirements.
- [x] 6.5 Add full-library memory migration UI with source and target selection, merge/overwrite confirmation, progress, result counts, and retry status.
- [x] 6.6 Update navigation and tests so agents, devices, and resource catalogs have clear ownership and no duplicate Skill configuration paths.

## 7. Roll out safely

- [x] 7.1 Add feature flags or compatibility versioning for agent-based bundle resolution and preserve the legacy projection during backfill.
- [x] 7.2 Run repository-level manager-api, console, and Python tests covering migration and compatibility paths.
- [x] 7.3 Verify MQTT gateway and Python WebSocket behavior remains unchanged for existing devices.
- [x] 7.4 Enable agent-based resolution after all bound agents have an initial published version and document rollback to the legacy projection.

## 8. Map requirements to executable acceptance

- [x] 8.1 Create an acceptance matrix mapping every scenario in the four capability specifications to an automated test, an end-to-end flow where applicable, fixture or seed data, and expected observable result.
- [x] 8.2 Add negative and authorization cases for agent load/update, model and credential resource access, publish, activate, rollback, device projection, legacy migration, and memory migration; verify rejected requests do not mutate data.
- [x] 8.3 Add concurrency and idempotency cases for publish, activation, rollback, capability refresh, memory migration, and retry; define the expected winner, status, and audit outcome for each race.
- [x] 8.4 Add contract fixtures for the Python-facing capability bundle and migration audit records, with compatibility assertions that fail on unapproved response-shape changes.
- [x] 8.5 Add end-to-end checks covering console -> manager-api -> Python runtime for draft/publish/version pinning, two-device capability isolation, and console -> manager-api -> memory provider for merge, overwrite, failure recovery, and retry.

## 9. Add console quality and style acceptance

- [x] 9.1 Define the acceptance baseline for the companion console using the existing Ant Design components and project style tokens, including typography, spacing, colors, control states, form validation, and notification patterns.
- [x] 9.2 Add browser checks for loading, empty, error, disabled, hover, focus, keyboard navigation, unsaved-change protection, responsive narrow viewport layout, text overflow, and semantic labels on all new agent, device projection, and migration views.
- [x] 9.3 Add visual regression snapshots for the agent editor, publish/rollback states, device effective-capability view, and memory migration flow at supported desktop and mobile widths; require review for baseline changes.
- [x] 9.4 Add accessibility checks for keyboard-only completion of the primary flows, focus visibility, accessible names and descriptions, color contrast, and error association with form fields.

## 10. Enforce merge and rollout gates

- [x] 10.1 Add CI jobs or repository checks that run manager-api tests, companion-console tests and build, Python tests, MQTT gateway tests, type checks, lint checks, and the relevant integration or end-to-end suites for this change.
- [x] 10.2 Add a migration dry-run check that reports total agents, agents missing initial published versions, legacy bindings projected, conflicts, skipped rows, and retryable failures; block enablement on unresolved conflicts or failed required checks.
- [x] 10.3 Add a pre-enable parity check comparing legacy and agent capability projections for migrated devices and fail when device identity, tool isolation, Skill policy, or version metadata differs unexpectedly.
- [x] 10.4 Add a rollback rehearsal that disables the feature flag or compatibility version, verifies legacy projection and existing MQTT/Python WebSocket behavior, and records the exact rollback command and health-check evidence.
- [x] 10.5 Define the release evidence record containing commit SHA, test commands and results, build artifacts, acceptance matrix, visual/accessibility results, migration report, conflict disposition, feature-flag state, rollback evidence, and sign-off owner.
- [x] 10.6 Mark 7.4 complete only after all gates pass, all bound agents have initial published versions, all conflicts have an explicit disposition, and the rollback rehearsal succeeds.
