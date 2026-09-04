# Zixuan Release Inventory

This inventory is the source baseline for the Zixuan product cutover. Retired names appear only to define migration inputs. It contains no credentials, device tokens, user content, or NVS payloads.

## Release Scope

The release contract version is `zixuan-cutover-v1`. All Java, Python, MQTT, console, web, proxy, storage, firmware, and assets artifacts must report this version before deployment.

The maintained product boards are `zhengchen-cam` and `bread-compact-wifi-s3cam`. Both require source, static, and build verification. Current release artifacts and physical acceptance exist only for `zhengchen-cam`; `bread-compact-wifi-s3cam` remains a release blocker until a matching build and real-device acceptance exist or a later approved change removes it from maintained scope.

## Runtime Services

The Java control plane is `manager-api`, currently built as `xiaozhi-esp32-api.jar` from the `xiaozhi` Java package. Its target artifact is `zixuan-manager-api.jar` from the `zixuan` package. The service listens on port 8002.

The Python runtime is currently `server/main/xiaozhi-server`, deployed as `ai-plush-xiaozhi-server` and exposing device WebSocket port 8000 plus HTTP port 8003. Its target directory, image, and service are `server/main/zixuan-server`, `ai-plush/zixuan-server`, and `zixuan-server`.

The Node gateway is currently `xiaozhi-mqtt-gateway`, with MQTT port 1883, UDP port 8884, and loopback management port 8007. Its target package and service identity are `zixuan-mqtt-gateway`.

The React control surface is `companion-console`. The Next.js public conversation client is `companion-web`; its retired playground entry has already been removed from the control surface, but its public conversation contract remains part of release verification.

The current production host uses the root `/opt/ai-plush-companion` and systemd units `ai-plush-manager-api`, `ai-plush-xiaozhi-server`, `ai-plush-mqtt-gateway`, `ai-plush-companion-web`, and `ai-plush-tencentdb-memory`. Target unit names must use the Zixuan identity and are installed only during the final cutover.

## HTTP And WebSocket Contracts

The current manager API root is `/xiaozhi`; the target is `/zixuan`. The current device WebSocket route is `/xiaozhi/v1/`; the target is `/zixuan/v1/`. The current OTA route is `/xiaozhi/ota/`; the target is `/zixuan/ota/`. The current virtual runtime routes start with `/xiaozhi/internal/playground`; the target starts with `/zixuan/internal/playground`.

Identity-neutral public conversation `/api/v1/`, service-secret `/internal/`, and vision `/mcp/` routes retain their current paths. Nginx must explicitly reject retired product routes instead of serving the console fallback.

## MQTT And UDP Contracts

The current device publish topic is `device-server`; the current reply topic is `devices/p2p/<device>`. Their targets are `zixuan/device-server` and `zixuan/devices/p2p/<device>`. UDP framing, encryption, sequence, and port 8884 remain unchanged. Client IDs retain board, MAC, and UUID semantics while product-owned default client metadata changes to Zixuan.

## Storage Namespaces

The current MySQL database is `xiaozhi_esp32_server`; the target is `zixuan_esp32_server`. Existing tables, primary keys, user ownership, device identity, Agent versions, capability bindings, conversations, memory references, and Liquibase history must be preserved.

Current Redis key families include `sys:params`, `sys:device:captcha:`, `agent:device:count:`, `agent:device:lastConnected:`, `device:address_book:all`, `device:debug:logs:`, public conversation bundles and quotas, web bootstrap sessions, and capability caches. Target business keys receive the `zixuan:` prefix. Migration must inventory additional deployed key families before write freeze and preserve type and TTL.

Product-owned upload and generated asset roots currently include manager-api `uploadfile`, wake-word package storage, Python `data`, runtime logs, and release archives. Target directories and manifests use Zixuan names. Provider-owned Memory Core data keeps its provider schema while product namespace fields move to the Zixuan contract.

## Firmware And Assets

`zhengchen-cam` currently uses `CONFIG_OTA_URL` ending in `/xiaozhi/ota/`, factory command `ni hao xiao zhi`, and display text `你好小智`. The target values use `/zixuan/ota/`, `ni hao zi xuan`, and `你好紫萱`. The board remains ESP32-S3 with its existing 16 MiB flash, partition table, 8 MiB assets partition, layout 2, and two 3 MiB wake-word slots.

`bread-compact-wifi-s3cam` keeps its own GPIO, audio, screen, camera, partition, and board configuration. It receives the same Zixuan product protocol and identity changes without copying the `zhengchen-cam` hardware implementation.

Bound devices must never receive a blank-NVS whole-flash image. Approved partition writes are bootloader, partition table, OTA data, application, and matching generated assets. Each device requires a pre-flash NVS backup and post-flash identity, Wi-Fi recovery, activation, binding, capability, and conversation evidence.

## External And Historical Exclusions

Authentic upstream repositories such as `78/xiaozhi-esp32` and `xinnan-tech/xiaozhi-esp32-server`, upstream service URLs such as `api.tenclass.net/xiaozhi/ota/`, third-party package and vendor symbols, executed Liquibase content, and historical design or acceptance records retain their original identity. Every exclusion must match `scripts/zixuan-brand-allowlist.json`; no unclassified exclusion is accepted.

## Required Evidence

Each source batch requires focused tests and a clean diff check. The release requires full Java, Python, gateway, console, web, firmware, OpenSpec, scanner, artifact, storage migration, install, rollback, and real-device evidence. The final architecture diagram must be regenerated from the renamed tree rather than edited to display a target state that the repository has not reached.
