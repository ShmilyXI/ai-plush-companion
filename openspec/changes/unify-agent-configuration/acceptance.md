# Acceptance Matrix

This matrix is part of the `unify-agent-configuration` change. A scenario is accepted only when the named automated check passes and the listed observable result is recorded in the release evidence.

| ID | Scenario | Required check | Observable result |
| --- | --- | --- | --- |
| AC-01 | Load an agent configuration | manager-api contract and authorized console flow | One response contains identity, prompt, models, voice, memory policy, Skills, devices, and active version metadata |
| AC-02 | Save agent settings | manager-api write test and console save flow | Changes remain in the agent draft and no device Skill write is made |
| AC-03 | Select a configured model | resource reference contract test | Only resource reference and non-secret overrides are persisted |
| AC-04 | Configure a missing credential inline | secret-redaction API test and browser flow | Credential status refreshes and the secret is absent from response, DOM, logs, and audit output |
| AC-05 | View a device's effective Skills | console browser flow and authorization test | Effective Skills and reasons are read-only; no device Skill ownership control exists |
| AC-06 | Device supports all requested Skills | capability projection unit and integration test | Bundle contains permitted Skills, tools, and parameters |
| AC-07 | Device lacks a required capability | projection negative test and console flow | Skill is marked unavailable with a concrete reason and omitted from runtime tools |
| AC-08 | Two devices use one agent | two-device runtime isolation test | Each bundle contains only that device's tool intersection |
| AC-09 | Python refreshes a device bundle | versioned Python contract test and WebSocket integration test | Identity, active version, Skills, and executable tools match the contract |
| AC-10 | Migrate a legacy binding | migration fixture test and audit assertion | Version mode, fixed version, overrides, priority, enabled state, and source audit are preserved |
| AC-11 | Publish an agent draft | manager-api integration test and console flow | New immutable snapshot is created with the complete configuration |
| AC-12 | Edit a published version | immutability negative test | In-place update is rejected and no published data changes |
| AC-13 | New connection after publish | Python connection integration test | New connection resolves the newly active version |
| AC-14 | Active version changes during a session | connection lifecycle test | Existing session keeps its captured version; next session uses the new version |
| AC-15 | Roll back an agent | activation audit and runtime integration test | Previous immutable version becomes active without content changes |
| AC-16 | Publish with an unavailable model | field-validation and transaction test | Publication fails with field-level reason and prior active version remains active |
| AC-17 | Two devices use one agent memory | namespace isolation test | Reads and writes stay within user, agent, and device namespace |
| AC-18 | Start or reject a migration | authorization and console confirmation flow | Eligible counts and mode are shown before confirmation; ineligible requests read or write nothing |
| AC-19 | Merge two libraries | provider integration test with duplicate fixtures | Unique source items import, duplicates skip, source remains unchanged, counts match |
| AC-20 | Overwrite and recover | provider failure-injection test | Target is replaced on success; failure restores or preserves target and never reports partial success |
| AC-21 | Inspect migration history | audit redaction and authorization test | Counts, mode, operator, timestamps, and outcome are visible without secrets or unrelated content |

## User-flow gates

The console acceptance suite must complete the agent edit, save draft, publish, activate, rollback, unavailable Skill, missing credential, and memory migration flows using keyboard navigation as well as pointer interaction. Each flow must pass at desktop and narrow viewport sizes with loading, empty, error, disabled, focus, and unsaved-change states covered.

## Release gates

The change cannot merge when manager-api tests, companion-console tests and build, Python tests, MQTT gateway tests, type checks, lint checks, or required integration tests fail. Visual regression, accessibility, secret-redaction, and Python bundle contract checks are mandatory for affected areas.

The change cannot enable agent-based resolution until the migration dry run reports every bound agent with an initial published version, every legacy binding either projected or explicitly conflicted, and no failed required migration step. A parity check must compare legacy and agent projections before enablement.

The release evidence must record the commit SHA, exact commands and results, build artifacts, acceptance matrix results, visual and accessibility results, migration report, conflict disposition, feature-flag state, rollback command, and post-rollback MQTT and Python WebSocket health checks.

The simulated cross-layer checks are implemented in `server/main/xiaozhi-server/tests/test_unify_agent_configuration_cross_layer.py`. They connect a fake manager publish response to the versioned Python capability cache, assert two-device tool isolation, and exercise provider merge plus failure recovery. The browser suite in `server/main/companion-console/e2e/login-quality.spec.ts` covers the corresponding console request flows. These are contract simulations; they do not replace live MQTT, hardware, production database, or provider connectivity evidence.
