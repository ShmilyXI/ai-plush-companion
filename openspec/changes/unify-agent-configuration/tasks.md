## 1. Establish the agent version model

- [ ] 1.1 Add persistence for agent drafts, immutable published versions, active version selection, and version activation audit records.
- [ ] 1.2 Define the version snapshot contract for identity, prompt, model and voice bindings, memory policy, and Skill bindings.
- [ ] 1.3 Add manager-api validation for model references, Skill references, Skill versions, required credentials, and publish transitions.
- [ ] 1.4 Backfill an initial published version for every existing agent and record the migration result.

## 2. Move Skill ownership to agents

- [ ] 2.1 Add agent-to-Skill version bindings that preserve version mode, fixed version, overrides, trigger priority, and enabled state.
- [ ] 2.2 Build a compatibility migration from enabled `ai_device_skill_mapping` rows into the owning agent's initial version.
- [ ] 2.3 Add conflict detection and audit output for legacy device bindings that cannot be projected without user review.
- [ ] 2.4 Change new management writes to update agent drafts and remove device-level Skill ownership from the primary console workflow.

## 3. Resolve effective device capabilities

- [ ] 3.1 Update the effective capability bundle service to resolve the device's active agent version before applying device capability filters.
- [ ] 3.2 Filter Skills and tools against reported hardware and device MCP tool snapshots while preserving connection and device isolation.
- [ ] 3.3 Return unavailable Skill reasons in management projections and exclude unsupported tools from the Python runtime bundle.
- [ ] 3.4 Preserve or version the Python-facing bundle contract and update Python cache invalidation when an agent version is published or activated.
- [ ] 3.5 Add runtime tests for two devices sharing an agent with different hardware and tool sets.

## 4. Implement draft, publish, and rollback workflows

- [ ] 4.1 Add agent draft read and update APIs that aggregate identity, models, voice, memory policy, Skills, and bound devices.
- [ ] 4.2 Add publish and activate/rollback APIs with immutable version enforcement and audit records.
- [ ] 4.3 Pin the resolved agent version for the lifetime of a Python connection and load the active version on the next connection.
- [ ] 4.4 Add manager-api tests for invalid publication, rollback, active-session consistency, and all-devices-follow-active-version behavior.

## 5. Repair and migrate device memory ownership

- [ ] 5.1 Stop using the shared agent `summaryMemory` field as the runtime source of truth for device memory and migrate summaries into device namespaces.
- [ ] 5.2 Define Python provider export, import, deduplication, backup, and restore operations for the supported memory providers.
- [ ] 5.3 Add an authorized one-time migration API restricted to source and target devices belonging to the same user and agent.
- [ ] 5.4 Implement merge mode with logical-content deduplication and overwrite mode with target backup or provider-supported recovery.
- [ ] 5.5 Record migration attempts, counts, modes, outcomes, and retry state without deleting source data.
- [ ] 5.6 Add tests for isolation, merge, overwrite success, failed overwrite recovery, authorization, and retry behavior.

## 6. Consolidate the companion console

- [ ] 6.1 Make the agent editor the primary workflow for identity, prompt, models, voice, memory policy, Skills, devices, and version publishing.
- [ ] 6.2 Add draft and publish state, version activation, rollback, and unsaved-change handling to the agent editor.
- [ ] 6.3 Add inline model credential configuration that uses reusable user-owned resources without exposing stored secrets.
- [ ] 6.4 Replace device Skill editing with an effective-capability projection that explains unavailable Skills and hardware requirements.
- [ ] 6.5 Add full-library memory migration UI with source and target selection, merge/overwrite confirmation, progress, result counts, and retry status.
- [ ] 6.6 Update navigation and tests so agents, devices, and resource catalogs have clear ownership and no duplicate Skill configuration paths.

## 7. Roll out safely

- [ ] 7.1 Add feature flags or compatibility versioning for agent-based bundle resolution and preserve the legacy projection during backfill.
- [ ] 7.2 Run repository-level manager-api, console, and Python tests covering migration and compatibility paths.
- [ ] 7.3 Verify MQTT gateway and Python WebSocket behavior remains unchanged for existing devices.
- [ ] 7.4 Enable agent-based resolution after all bound agents have an initial published version and document rollback to the legacy projection.
