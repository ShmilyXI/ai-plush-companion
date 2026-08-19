## Context

The repository currently has a Java `manager-api`, a React companion console, a Python `xiaozhi-server`, and a separate MQTT/UDP gateway. The Python runtime owns model adapters and conversation execution. The Java service owns persistence, account and device binding, model metadata, capability management, and the configuration projection consumed by Python.

The console already has an agent/profile editor and version history, but Skills are still configured under devices. Runtime capability bundles are resolved from device-to-Skill rows, while agent configuration is resolved separately. Long-term memory is namespaced by user, agent, and device, but management updates currently write a summary back to the shared agent row.

The change keeps these process boundaries. It introduces a coherent agent aggregate and makes the effective runtime bundle a projection of an active agent version onto a device's reported capabilities.

## Goals / Non-Goals

**Goals:**

- Make the agent the single configuration surface for identity, prompt, models, voice, memory policy, and Skills.
- Freeze all behavior-affecting agent settings in immutable published versions.
- Make every bound device follow the agent's active published version, with connection-level consistency.
- Preserve device isolation for long-term memory and support full one-time migration with merge or overwrite modes.
- Keep model credentials reusable at user scope and configurable inline from the agent editor.
- Preserve existing MQTT, WebSocket, and Python runtime protocols through compatibility projection.

**Non-Goals:**

- Replacing the Java backend framework.
- Rewriting the MQTT gateway or Python model/provider implementations.
- Moving all memory to agent scope; memory remains device-scoped in this change.
- Supporting per-device agent-version pinning or continuous memory synchronization.
- Migrating unfinished dialogue context between devices.

## Decisions

The persisted agent remains the existing agent/profile identity, while a published version becomes the immutable runtime contract. A version snapshot includes user-facing identity and prompt fields, model and voice bindings, memory policy, Skill bindings including version strategy and parameters, and the effective compatibility metadata needed for resolution. Draft edits remain mutable until publish. Published versions cannot be edited in place.

The active version is selected at the agent level. A device stores only its agent binding and hardware/tool facts. At connection or runtime-bundle refresh, `manager-api` resolves the device's active agent version and filters its Skills against the device capability snapshot. Unsupported Skills remain visible as unavailable in management responses but are excluded from Python's executable tool bundle. Existing sessions retain the version captured at connection time.

The capability bundle endpoint remains the Python-facing boundary. Its implementation changes from reading enabled device Skill mappings to resolving the device's active agent version and then applying device capability checks. A compatibility path can materialize legacy device Skill rows into agent bindings during migration, but new writes use the agent model.

Long-term memory keeps the existing logical namespace shape that includes user, agent, and device. The shared agent `summaryMemory` field stops being the source of truth for device memory summaries. Memory management reads and writes the resolved device namespace. A migration operation exports the complete source library, validates the target, and then performs either a deduplicating merge or a replacement of the target library. The source namespace is never deleted. Replacement uses a target backup or provider-supported transactional equivalent so a failed import can be retried without silently losing the target library.

Model credentials remain reusable user-owned resources. Agent versions store references and non-secret overrides, never raw secrets. The agent editor can invoke the existing resource APIs inline and then refresh selectable model options.

The console navigation presents agents as the primary composition surface, devices as hardware management, and resources as reusable model/Plugin/MCP catalogs. Device detail no longer owns Skill selection; it shows the effective Skills and any hardware incompatibility as read-only projection information.

## Risks / Trade-offs

- **Legacy device Skill rows may disagree with agent bindings** → Provide a one-time migration projection, audit conflicts, and make new runtime resolution prefer the published agent version after migration.
- **Changing an active version affects many devices** → Require explicit publish confirmation, keep active sessions pinned to their connection version, and support rollback by reactivating an immutable prior version.
- **Memory providers differ in transactional support** → Define export/import operations at the Python provider boundary, use staging and backups for replacement, and expose partial failure status rather than claiming atomicity where the provider cannot provide it.
- **The old shared summary field can leak data across devices** → Stop using it for runtime device memory and migrate summaries into the device namespace before enabling memory migration.
- **Unsupported Skills may confuse users** → Keep them visible with a concrete unavailable reason while excluding them from the executable runtime bundle.
- **Inline credential editing can broaden secret exposure** → Keep secret values server-side, return only credential status, and preserve existing secret redaction and audit behavior.

## Migration Plan

Introduce the agent-version tables and APIs while retaining current agent and device columns. Backfill an initial published version for each existing agent from its current profile/model state. Convert enabled device Skill bindings into agent Skill bindings, preserving version mode, fixed version, overrides, and priority; record conflicts for review.

Update effective bundle resolution and the Python contract behind a feature flag or compatibility version. During the transition, devices without an active agent version continue receiving the legacy projection. Once all bound agents have an initial published version, switch new console writes and runtime resolution to the agent projection.

Move device memory summaries into the existing device namespace before exposing migration UI. Add merge and replacement operations, audit records, and retry status. Keep the source namespace untouched in every migration mode. Rollback of the feature consists of disabling new agent-version writes and serving the legacy configuration projection; published data remains available for a later retry.

## Open Questions

The first implementation should confirm whether a published version can be activated for an agent with no bound devices and which audit retention period is required for migration records. These do not change the core ownership model.
