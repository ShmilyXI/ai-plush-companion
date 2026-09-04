# Zixuan Cutover Runbook

This runbook applies only to the release identified by `deploy/zixuan/release-manifest.json`. Do not mix artifacts from different manifest revisions.

## Preflight

Run `scripts/verify-zixuan-release.sh` from the repository root. Confirm the release manifest, storage dry-run reports, rollback snapshots, service artifacts, firmware application/assets pairs, device inventory, and current NVS backups are present before changing any running service.

## Install

Copy the verified release tree to `/opt/zixuan`, install `deploy/zixuan/zixuan.env.example` as `/etc/zixuan/zixuan.env`, and populate secrets outside version control. Install the five service units from `deploy/zixuan`, validate `nginx.conf`, then start the manager API, runtime, Memory Core, MQTT gateway, companion web, and Nginx in that order.

The proxy must return HTTP 410 for retired product routes. The released routes are `/zixuan/`, `/zixuan/v1/`, and `/zixuan/ota/`; `/api/v1/`, `/internal/`, and `/mcp/` remain identity-neutral.

## Device Flash

Use the board entry in `firmware/releases/zixuan-release-manifest.json`. Back up the 16 KiB NVS partition at `0x9000` before writing. Flash only bootloader at `0x0`, partition table at `0x8000`, OTA data at `0xd000`, `zixuan.bin` at `0x20000`, and matching `generated_assets.bin` at `0x800000`. Do not flash `merged-binary.bin` to a bound device.

## Verification

Verify management login, capability management, public text and audio sessions, runtime health, memory, MQTT reconnect, OTA and obsolete-route rejection. For each device record its MAC, board, firmware and assets checksums, Agent version, endpoint set, NVS hashes, activation and binding state, and the observed startup, display, audio, camera, button, wake-word, interruption and reconnect results.

## Rollback

Stop the Zixuan units together. Run `scripts/zixuan-rollback.sh` with the release rollback manifest, restore the matching proxy and service artifacts, restore the database, Redis and object snapshots, and reflash affected devices with their matching previous board package. Start the previous stack and repeat its management, conversation, MQTT and memory health checks before reopening writes.
