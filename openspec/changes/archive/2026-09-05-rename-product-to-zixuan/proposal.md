## Why

The product still exposes the upstream `xiaozhi` identity across source paths, runtime services, protocols, data namespaces, firmware defaults, and user-facing text even though the product identity is now Zixuan. A partial rename would create mixed deployments and leave devices or stored data attached to obsolete routes, so the change must be performed as one versioned cutover with explicit exclusions and rollback evidence.

## What Changes

- **BREAKING** Rename product-owned machine identifiers from `xiaozhi` to `zixuan`, including the Python service directory, Java root package and artifact, MQTT gateway package and service identity, deployment units, configuration keys, generated artifacts, and product-owned firmware symbols.
- **BREAKING** Move product-owned HTTP and WebSocket endpoints from `/xiaozhi` to `/zixuan`, and move MQTT topics to the `zixuan/` namespace. New services reject obsolete product routes and topics after cutover.
- **BREAKING** Move the product database to `zixuan_esp32_server`, Redis data to `zixuan:` keys, and product-owned object/log paths to the Zixuan namespace using audited migrations that preserve business ownership and TTLs.
- Rename user-visible product text to `紫萱`, including console titles, device messages, logs, default prompts, and the factory wake word `你好紫萱`.
- Rebuild every supported board's application and assets, preserve required NVS identity data, then reactivate and rebind devices against the Zixuan endpoints.
- Add a tracked allowlist for third-party package names, upstream URLs, vendor/SDK symbols, executed Liquibase identities, and historical audit records that must retain their original spelling.
- Add release gates for brand scanning, cross-language contracts, data migration parity, full service rollback, and real-device recovery.

## Capabilities

### New Capabilities

- `zixuan-product-identity`: Defines product-owned display names and machine identifiers, plus the allowlisted external and historical identities that remain unchanged.
- `zixuan-runtime-contract`: Defines the renamed HTTP, WebSocket, MQTT, service, package, and deployment contracts and rejects obsolete product entry points.
- `zixuan-data-namespace`: Defines database, Redis, object, log, migration, verification, and rollback behavior for the renamed storage namespaces.
- `zixuan-device-cutover`: Defines firmware, assets, NVS preservation, activation, binding, OTA, and real-device acceptance for the renamed product.

### Modified Capabilities

None. No main OpenSpec capability specifications exist yet; this change establishes the rename contracts.

## Impact

The change affects the Java manager API and all Java package paths, the Python runtime directory and deployment image, the MQTT/UDP gateway, companion console and web clients, ESP32 firmware and board assets, CI and release scripts, Nginx and systemd configuration, MySQL, Redis, object and log storage, generated OpenAPI documents, tests, and operational runbooks. Existing devices and clients using old product routes or topics are incompatible after cutover and must be upgraded as part of the same release window.
