## Why

The current console makes users assemble one companion across separate profile, model, device, and capability screens. Skills are bound to devices while the rest of the behavior is bound to an agent, so changing devices can leave personality, tools, and memory in an unexpected state.

The project needs one coherent intelligent-agent model without replacing the current Java backend, MQTT gateway, or Python conversation runtime.

## What Changes

- Make the intelligent agent the primary user-facing configuration unit for personality, system prompt, models, voice, memory policy, and Skills.
- Move Skill selection and Skill version policy from device bindings to immutable published agent versions.
- Keep device hardware facts and reported tools on the device, and compute an effective runtime bundle by intersecting the active agent version with device capabilities.
- Add draft and publish semantics for agent configuration. All devices bound to an agent follow its active published version; active sessions keep the version selected when they connected.
- Keep long-term memory isolated by device. Add one-time full-library migration between devices of the same agent with merge and overwrite modes; source memories remain unchanged.
- Treat model credentials as reusable user-owned resources while allowing configuration from the agent editor.
- Consolidate the console workflow around intelligent agents, devices, and resource libraries while retaining existing backend framework and MQTT/Python runtime paths.

## Capabilities

### New Capabilities

- `agent-configuration`: Configure an intelligent agent as the single composition of identity, models, voice, memory policy, and Skills.
- `agent-version-publishing`: Save drafts, publish immutable agent versions, activate or roll back versions, and resolve the active version for bound devices.
- `agent-device-capability-projection`: Project an agent version onto a device by filtering agent Skills against reported hardware and device tools.
- `device-memory-migration`: Migrate the complete long-term memory library between devices of the same agent using merge or overwrite semantics.

### Modified Capabilities

None. The repository has no checked-in OpenSpec capability specifications yet; the new capabilities will establish the initial contracts.

## Impact

The change affects `server/main/manager-api` aggregation and persistence APIs, companion-console navigation and editors, capability bundle resolution, memory management APIs, and the Python runtime configuration contract. MQTT gateway behavior and the existing Python WebSocket conversation path remain integration boundaries rather than rewrite targets. Existing device bindings and legacy configuration endpoints need a compatibility projection during migration.
