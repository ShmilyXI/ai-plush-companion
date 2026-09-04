## Context

The repository is a derivative of upstream Xiaozhi projects but is now a separate product named Zixuan. Product-owned names remain embedded in Java packages, the Python service directory, runtime routes, deployment units, storage namespaces, firmware defaults, tests, and documentation. The currently deployed system also uses the old service and route identities.

This is a coordinated breaking release, not a compatibility migration. Existing devices can be physically reflashed and rebound. Device NVS contains Wi-Fi credentials, UUIDs, binding data, and settings, so the release must preserve identity data and must never replace a bound device with a blank-NVS merged image.

## Goals / Non-Goals

**Goals:**

- Make `zixuan` the sole product-owned machine identity and `紫萱` the sole user-visible product identity.
- Move product-owned source paths, packages, services, routes, topics, storage namespaces, firmware defaults, generated artifacts, tests, and documentation to the new identity.
- Cut over Java, Python, MQTT, clients, data, and firmware as one release with deterministic preflight and rollback evidence.
- Retain genuine third-party and historical identities through a reviewed allowlist.

**Non-Goals:**

- Renaming upstream repositories, third-party packages, vendor SDK symbols, licenses, or model names.
- Rewriting executed Liquibase changesets or historical audit records.
- Changing conversation semantics, provider orchestration, hardware pin assignments, partition layout, or capability ownership.
- Keeping old product routes, topics, environment variables, or service names as runtime aliases after cutover.

## Decisions

Machine identifiers use ASCII `zixuan`; Chinese `紫萱` is restricted to user-visible copy. This avoids invalid Java packages, environment variables, paths, URLs, and firmware symbols while preserving a consistent display brand.

A tracked scanner and allowlist define completion. The scanner inspects tracked file contents and paths. Each remaining case-insensitive `xiaozhi` or user-visible `小智` match must identify a permitted class: upstream URL or package, vendor/SDK symbol, executed migration identity, or historical evidence. Generated build directories and local logs are not inputs.

The Java package moves from `xiaozhi` to `zixuan` in one commit that also updates imports, tests, Maven coordinates, MyBatis namespaces, reflection strings, and application metadata. Maintaining a bridge package was rejected because the release explicitly disallows a mixed runtime identity.

The Python directory moves from `server/main/xiaozhi-server` to `server/main/zixuan-server`. CI, Docker and Compose files, systemd units, volume mounts, scripts, documentation, and generated OpenAPI names move in the same batch. Internal Python module names that do not contain the product identity remain unchanged.

Product routes move from `/xiaozhi` to `/zixuan`. The device WebSocket is `/zixuan/v1/`, OTA is `/zixuan/ota/`, and product-owned internal playground paths use `/zixuan/internal/`. Generic `/internal/` service-secret routes and `/mcp/` routes retain their functional names because they do not contain the retired identity. The public conversation API remains `/api/v1/`.

MQTT receives an explicit `zixuan/` topic namespace. Device publish and reply topics, gateway subscriptions, OTA responses, fixtures, and firmware clients change together. The existing unprefixed topics are rejected after cutover.

MySQL migrates by creating and validating `zixuan_esp32_server`, not by editing a schema name in place. The migration exports the old database, imports the new database, validates table counts and selected business invariants, then changes service credentials during the maintenance window. Executed Liquibase files and their identifiers are copied unchanged.

Redis migration enumerates business keys, copies them to `zixuan:` names, preserves value type and TTL, and compares counts and digests before old keys are removed. Unknown transient keys are excluded and reported. Object and log data use equivalent copy, verify, switch, and cleanup phases.

Every supported product board gets its own application and matching assets image. The factory wake word becomes `你好紫萱`. Flashing covers bootloader, partition table, OTA data, application, and generated assets while preserving NVS. Device service configuration and binding state are cleared only through the documented reactivation procedure after an NVS backup exists.

Production cutover is atomic at the release level. New artifacts are built before write freeze. After database and storage snapshots, data migration runs, all services and proxies switch, then devices are reflashed and rebound. Rollback stops the new stack and restores the complete old database, Redis/object snapshots, service artifacts, proxy configuration, and matching firmware package. Cross-version service combinations are unsupported.

## Risks / Trade-offs

- A partial protocol deployment disconnects devices and browser clients -> gate deployment on one version manifest covering Java, Python, gateway, clients, proxy, and firmware.
- Closing the old OTA route strands old firmware -> inventory and physically flash supported devices before final old-route removal.
- Database or Redis migration can create split ownership -> freeze writes, validate counts, TTLs, digests, Agent versions, bindings, and memory ownership before enablement.
- Mechanical replacement can corrupt upstream or Liquibase identities -> require the scanner allowlist and review every permitted legacy match.
- Java package movement can leave reflection or XML references behind -> compile all production and test sources and scan built artifacts for retired package names.
- Factory assets and NVS can disagree after flashing -> build application/assets as a pair and verify layout, wake-word slots, UUID, Wi-Fi, and binding behavior on real hardware.
- The release has a maintenance window and no live compatibility path -> retain complete old artifacts and snapshots until post-cutover device and public-session acceptance passes.

## Migration Plan

Build and verify the scanner first. Rename source and build identities in isolated commits while keeping the branch unreleased. Produce all Zixuan service, client, and firmware artifacts and record their checksums. Run MySQL, Redis, and object-store migrations in dry-run mode against snapshots and generate a parity report.

At cutover, freeze management writes, create final data and artifact snapshots, run the verified migrations, install the Zixuan service units and Nginx configuration, start the new stack, and confirm old routes and topics reject traffic. Flash and reactivate each supported device using its board-specific package while preserving NVS backup evidence. Validate console login, capability management, public text and audio sessions, device audio, interruption, reconnect, OTA, wake word, and capability reporting.

Rollback is one operation: stop all Zixuan services, restore old proxy and service artifacts, restore the old data snapshots, and reflash affected devices with the matching old release package. No individual layer rolls back alone.

## Open Questions

There are no unresolved product decisions. The implementation inventory must derive the exact supported board list and storage key set from the current repository and deployed environment before the destructive cutover phase.
