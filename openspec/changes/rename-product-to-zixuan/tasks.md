## 1. Establish the rename gate and release inventory

- [x] 1.1 Add a tracked brand allowlist that classifies upstream URLs and packages, vendor or SDK symbols, executed migration identities, and historical evidence.
- [x] 1.2 Add a scanner for retired names in tracked paths and contents, with machine-readable output and a failing unclassified-match exit status.
- [x] 1.3 Record the supported board inventory, runtime services, public and internal routes, MQTT topics, storage namespaces, and deployed artifact inputs.
- [x] 1.4 Add CI coverage for the scanner and inventory contract before renaming production code.

## 2. Rename the Java control plane

- [x] 2.1 Move Java main and test packages from `xiaozhi` to `zixuan` and update imports, reflection strings, MyBatis namespaces, and Spring configuration.
- [x] 2.2 Rename the Maven group, artifact, application metadata, generated jar, documentation title, and product-owned Java configuration identifiers.
- [x] 2.3 Preserve executed Liquibase files and identities while adding a new Zixuan migration boundary for future changes.
- [x] 2.4 Run the full manager-api test suite, package build, package-path scan, and startup smoke test.

## 3. Rename the Python runtime

- [x] 3.1 Move `server/main/xiaozhi-server` to `server/main/zixuan-server` and update repository imports, scripts, CI, Compose, Docker, documentation, and generated API filenames.
- [x] 3.2 Rename product-owned Python application, image, log, configuration, and service metadata without changing provider or protocol semantics.
- [x] 3.3 Update tests and fixtures to resolve only the Zixuan runtime path and reject obsolete local service configuration.
- [x] 3.4 Run Python compile checks, the full Python suite, image build, and runtime startup smoke test from the new directory.

## 4. Rename HTTP and WebSocket contracts

- [ ] 4.1 Introduce one shared Zixuan route contract for manager API, device WebSocket, OTA, internal playground, console, companion web, gateway, and firmware consumers.
- [ ] 4.2 Move product-owned `/xiaozhi` routes to `/zixuan` across Java, Python, Nginx, clients, tests, and OpenAPI artifacts.
- [ ] 4.3 Add negative contract tests proving retired product routes are rejected after cutover while `/api/v1`, `/internal`, and `/mcp` remain available under their identity-neutral paths.
- [ ] 4.4 Run cross-language route, authentication, public conversation, OTA, device-control, and proxy tests.

## 5. Rename the MQTT gateway contract

- [ ] 5.1 Rename the gateway package, service, container, default client metadata, environment variables, logs, and release artifact.
- [ ] 5.2 Move device publish and reply topics into the configured `zixuan/` namespace across gateway, Java OTA responses, firmware, tests, and documentation.
- [ ] 5.3 Add rejection tests proving retired unprefixed product topics cannot enter a released device or Python session.
- [ ] 5.4 Run gateway syntax, authentication, command correlation, reconnect, and audio bridge tests.

## 6. Rename product UI and generated metadata

- [ ] 6.1 Rename all user-visible product titles, prompts, device messages, logs, and default copy from the retired identity to `紫萱`.
- [ ] 6.2 Rename product-owned frontend modules, API helpers, storage keys, generated OpenAPI files, architecture artifacts, and release archives to `zixuan`.
- [ ] 6.3 Update visual baselines and accessibility assertions only for reviewed Zixuan identity changes.
- [ ] 6.4 Run companion-console and companion-web lint, type checks, tests, builds, Playwright, and artifact scans.

## 7. Implement storage migration and rollback

- [ ] 7.1 Add a MySQL dry-run and migration tool that creates `zixuan_esp32_server`, preserves Liquibase history, and validates counts and ownership invariants.
- [ ] 7.2 Add a Redis migration tool that inventories business keys, copies them to `zixuan:` names, preserves types and TTLs, validates digests, and reports skipped transient keys.
- [ ] 7.3 Add object and log namespace migration with checksums, explicit switch state, and recoverable source snapshots.
- [ ] 7.4 Add one release migration report and one complete rollback command that restore matching old storage and service artifacts.
- [ ] 7.5 Test dry-run idempotency, conflict handling, partial failure, retry, and full rollback without changing production data.

## 8. Rebuild firmware and assets

- [ ] 8.1 Change product-owned firmware identity, route and topic configuration, UI text, build metadata, and factory wake word to Zixuan for every supported board.
- [ ] 8.2 Regenerate board-specific application and assets pairs and record checksums, partition layout, wake-word layout, and build toolchain in release manifests.
- [ ] 8.3 Add static and simulated tests for obsolete configuration rejection, activation recovery, OTA, heartbeat, MQTT topics, capability reporting, camera absence, and NVS protection.
- [ ] 8.4 Run the full firmware test suite and build every supported product board from its own `config.json`.

## 9. Build the coherent Zixuan release

- [ ] 9.1 Add Zixuan Nginx, systemd, container, environment, and deployment manifests without altering the currently running production stack.
- [ ] 9.2 Build Java, Python, gateway, console, web, firmware, and assets artifacts and bind their checksums to one rename contract version.
- [ ] 9.3 Run the brand scanner against source paths, source content, built artifacts, deployment manifests, and release archives; resolve every non-allowlisted match.
- [ ] 9.4 Rehearse installation and complete rollback in a disposable environment and record health checks for manager API, public sessions, Python runtime, MQTT, memory, and OTA.

## 10. Cut over production and devices

- [ ] 10.1 Inventory reachable devices and approve the maintenance window only after all artifacts, migrations, checks, snapshots, and rollback evidence pass.
- [ ] 10.2 Freeze writes, capture final database, Redis, object, configuration, and artifact snapshots, then execute the validated data migration.
- [ ] 10.3 Install and start the complete Zixuan stack, switch proxy and service configuration, and verify obsolete product routes, topics, and settings are rejected.
- [ ] 10.4 Back up NVS, flash the correct application and assets pair, reactivate, and rebind each supported physical device without blank-NVS whole-flash writes.
- [ ] 10.5 Complete real-device startup, display, audio, camera, buttons, capability, wake-word, conversation, interruption, reconnect, OTA, and return-to-service acceptance.
- [ ] 10.6 Publish the release evidence, regenerate the architecture diagram from the Zixuan tree, and archive the change only after the final scanner and all gates pass.
