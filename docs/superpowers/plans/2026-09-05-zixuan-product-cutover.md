# Zixuan Product Cutover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every product-owned Xiaozhi identity with `zixuan` or `紫萱` and release one coherent Java, Python, MQTT, client, storage, and firmware stack without renaming genuine upstream or immutable historical identities.

**Architecture:** The change is executed as a versioned release cutover. Source identities and protocols are renamed on the branch, storage migrations run against snapshots, and production changes only after all service and firmware artifacts share one contract version and rollback bundle.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Liquibase, MySQL, Redis, Python 3.12, aiohttp, Node.js, React, Next.js, MQTT/UDP, ESP-IDF, OpenSpec, Vitest, Playwright, pytest.

---

### Task 1: Establish the brand gate and inventory

**Files:**
- Create: `scripts/zixuan-brand-allowlist.json`
- Create: `scripts/verify-zixuan-brand.mjs`
- Create: `scripts/verify-zixuan-brand.test.mjs`
- Create: `docs/zixuan-release-inventory.md`
- Modify: `.github/workflows/unify-agent-configuration.yml`

- [ ] **Step 1: Write the failing scanner tests**

Create fixtures in the Node test with a product path, product content, upstream URL, executed migration identity, and vendor symbol. Assert that only allowlisted external and historical cases pass.

```js
assert.equal(scan([{ path: 'server/main/xiaozhi-server/app.py', text: '' }], allowlist).ok, false)
assert.equal(scan([{ path: 'README.md', text: 'https://github.com/78/xiaozhi-esp32' }], allowlist).ok, true)
assert.equal(scan([{ path: 'firmware/main/example.cc', text: 'XIAOZHI_PRODUCT_NAME' }], allowlist).ok, false)
```

- [ ] **Step 2: Run the tests and confirm the missing scanner fails**

Run `node --test scripts/verify-zixuan-brand.test.mjs` and expect failure because the scanner module does not exist.

- [ ] **Step 3: Implement the scanner and reviewed allowlist**

The scanner must use `git ls-files -z`, inspect paths and UTF-8 text, report JSON with `violations`, `allowed`, and category counts, and exit nonzero for every unclassified case-insensitive `xiaozhi` or user-visible `小智` match. Allowlist entries must match a narrow path plus regular expression and carry one of `upstream`, `vendor`, `migration-history`, or `historical-evidence`.

- [ ] **Step 4: Record the current release inventory**

Document the supported `zhengchen-cam` board, Java/Python/MQTT/console/web services, ports, `/xiaozhi` routes, MQTT topics, database, Redis patterns, object/log roots, production units, and current release checksums. Mark additional upstream boards as unsupported until explicitly promoted.

- [ ] **Step 5: Add the scanner to CI and verify the initial report**

Run `node --test scripts/verify-zixuan-brand.test.mjs` and `node scripts/verify-zixuan-brand.mjs --json`. The unit test must pass and the repository scan must fail with the measured pre-rename violations.

- [ ] **Step 6: Commit the gate**

```bash
git add scripts/zixuan-brand-allowlist.json scripts/verify-zixuan-brand.mjs scripts/verify-zixuan-brand.test.mjs docs/zixuan-release-inventory.md .github/workflows/unify-agent-configuration.yml
git commit -m "test: establish zixuan identity gate"
```

### Task 2: Rename the Java control plane

**Files:**
- Move: `server/main/manager-api/src/main/java/xiaozhi` to `server/main/manager-api/src/main/java/zixuan`
- Move: `server/main/manager-api/src/test/java/xiaozhi` to `server/main/manager-api/src/test/java/zixuan`
- Modify: `server/main/manager-api/pom.xml`
- Modify: `server/main/manager-api/src/main/resources/mapper/**/*.xml`
- Modify: `server/main/manager-api/src/main/resources/application*.yml`

- [ ] **Step 1: Add package and artifact assertions to the brand scanner test**

Assert the released Java source root is `zixuan`, the POM coordinates are `zixuan:zixuan-esp32-api`, and compiled class paths contain no product-owned `xiaozhi/` package.

- [ ] **Step 2: Move packages and update Java references**

Use `git mv` for both package roots, then replace product-owned `package xiaozhi`, `import xiaozhi`, mapper namespaces, reflection class names, application metadata, Maven group/artifact/name, and jar references with `zixuan`. Do not edit SQL files already listed as executed Liquibase history.

- [ ] **Step 3: Add a new migration boundary**

Create a new dated Liquibase changeset whose author and new objects use Zixuan naming. Keep every prior changeset file and checksum unchanged.

- [ ] **Step 4: Verify Java**

Run:

```bash
cd server/main/manager-api
export REPO_JDK21="$(cd ../../.. && pwd)/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home"
JAVA_HOME="$REPO_JDK21" PATH="$REPO_JDK21/bin:$PATH" mvn -DskipTests=false test
JAVA_HOME="$REPO_JDK21" PATH="$REPO_JDK21/bin:$PATH" mvn -DskipTests package
jar tf target/zixuan-esp32-api.jar | rg 'BOOT-INF/classes/xiaozhi/' && exit 1 || true
```

- [ ] **Step 5: Commit Java identity**

Commit only the Java package move, resources, tests, POM, and new changeset with message `refactor: rename java control plane to zixuan`.

### Task 3: Rename the Python runtime and deployment identity

**Files:**
- Move: `server/main/xiaozhi-server` to `server/main/zixuan-server`
- Modify: `server/main/README.md`
- Modify: `.github/workflows/unify-agent-configuration.yml`
- Modify: `.gitignore`
- Modify: `scripts/*.sh`
- Modify: `mqtt-gateway/start-local.sh`

- [ ] **Step 1: Add new-path and obsolete-path tests**

Extend `scripts/verify-architecture-baseline.mjs` so `server/main/zixuan-server` is required and `server/main/xiaozhi-server` is rejected. Update scanner tests to require Zixuan Docker image, Compose service, systemd unit, log path, and generated API filename.

- [ ] **Step 2: Run the path tests and confirm they fail**

Run `node scripts/verify-architecture-baseline.mjs` and expect the missing Zixuan runtime failure.

- [ ] **Step 3: Move the runtime and update owners**

Use `git mv server/main/xiaozhi-server server/main/zixuan-server`. Update tracked CI, Dockerfiles, Compose files, scripts, local gateway paths, documentation, generated OpenAPI names, image names, service names, volume roots, and test path references.

- [ ] **Step 4: Verify Python and images**

Run:

```bash
.venv312/bin/python -m compileall -q server/main/zixuan-server
cd server/main/zixuan-server
../../../.venv312/bin/python -m pytest -q
docker build -t ai-plush/zixuan-server:cutover .
```

- [ ] **Step 5: Commit runtime identity**

Commit the directory move and all tracked reference changes with message `refactor: rename python runtime to zixuan`.

### Task 4: Rename HTTP, WebSocket, and MQTT contracts

**Files:**
- Modify: `server/main/zixuan-server/core/http_server.py`
- Modify: `server/main/zixuan-server/core/websocket_server.py`
- Modify: `server/main/zixuan-server/core/api/ota_handler.py`
- Modify: `server/main/manager-api/src/main/java/zixuan/**`
- Modify: `server/main/companion-console/src/api/**`
- Modify: `server/main/companion-web/**`
- Modify: `mqtt-gateway/app.js`
- Modify: `mqtt-gateway/utils/mqtt_config_v2.js`
- Modify: `firmware/main/protocols/mqtt_protocol.*`
- Create: `docs/api/zixuan-server-openapi.json`

- [ ] **Step 1: Change contract tests before implementations**

Update route fixtures to require `/zixuan`, `/zixuan/v1/`, `/zixuan/ota/`, and `/zixuan/internal/playground`. Update MQTT fixtures to require `zixuan/device-server` and `zixuan/devices/p2p/<device>`. Add negative cases for old routes and topics.

- [ ] **Step 2: Run focused cross-layer tests and confirm old contracts fail**

Run Python HTTP/WebSocket/OTA tests, Java controller/config tests, gateway tests, frontend API tests, and firmware MQTT tests named by the changed fixtures.

- [ ] **Step 3: Implement one shared contract per layer**

Replace product route and topic literals with named constants in Java, Python, Node, TypeScript, and firmware. Preserve `/api/v1`, `/internal`, and `/mcp` unchanged. Ensure Nginx rejects `/xiaozhi` rather than serving the SPA fallback.

- [ ] **Step 4: Regenerate API documents and verify contracts**

Run `node scripts/generate-runtime-openapi.mjs`, Redocly lint, Java tests, Python public/device tests, gateway tests, frontend tests, and firmware protocol tests.

- [ ] **Step 5: Commit protocol identity**

Commit all protocol constants, consumers, fixtures, and generated documents with message `feat: move runtime protocols to zixuan namespace`.

### Task 5: Rename product UI and metadata

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `server/main/companion-console/src/**`
- Modify: `server/main/companion-web/**`
- Modify: `server/main/manager-mobile/**`
- Modify: `docs/**`

- [ ] **Step 1: Add visible identity assertions**

Update console, web, and mobile tests to require `紫萱管理台`, Zixuan storage keys, and Zixuan API helper names while rejecting user-visible `小智`.

- [ ] **Step 2: Rename user-visible and product-owned metadata**

Change application titles, prompts, errors, logs, source module names such as `xiaozhiModels`, storage keys, generated artifact names, and current documentation. Preserve allowlisted upstream citations and immutable historical evidence.

- [ ] **Step 3: Verify clients and visual baselines**

Run console lint, 636+ Vitest tests, production build, 18+ Playwright tests, companion-web type checks, Vitest, and production build. Review and update only screenshots whose visible brand text changed.

- [ ] **Step 4: Commit UI identity**

Commit current product documentation and client changes with message `feat: present zixuan product identity`.

### Task 6: Implement storage migration and rollback tools

**Files:**
- Create: `scripts/zixuan-migrate-mysql.sh`
- Create: `scripts/zixuan-migrate-redis.py`
- Create: `scripts/zixuan-migrate-storage.sh`
- Create: `scripts/zixuan-cutover-report.py`
- Create: `scripts/zixuan-rollback.sh`
- Create: `tests/test_zixuan_storage_migration.py`

- [ ] **Step 1: Write snapshot-based failing migration tests**

Use disposable MySQL and Redis fixtures. Assert dry-run makes no writes, import preserves table counts and Liquibase checksums, Redis preserves types and TTLs, retries are idempotent, and injected failure leaves source data recoverable.

- [ ] **Step 2: Implement MySQL migration**

The shell tool must require explicit source and target names, refuse equal names, dump with routines/triggers/events, create `zixuan_esp32_server`, import, compare tables and ownership queries, and emit a JSON report without credentials.

- [ ] **Step 3: Implement Redis and storage migration**

The Python tool must use SCAN, DUMP, PTTL, and RESTORE with deterministic target keys and digest reporting. The storage shell tool must copy product-owned object/log roots, compare file counts and SHA-256 manifests, and retain source snapshots.

- [ ] **Step 4: Implement complete rollback**

The rollback tool must require a release manifest and restore matching database, Redis, object/log, proxy, services, and artifact references. It must stop if any checksum is missing or mismatched.

- [ ] **Step 5: Verify and commit migration tooling**

Run `.venv312/bin/python -m pytest -q tests/test_zixuan_storage_migration.py` and two consecutive dry runs. Commit with message `feat: add zixuan storage cutover tooling`.

### Task 7: Rebuild firmware and assets

**Files:**
- Modify: `firmware/main/Kconfig.projbuild`
- Modify: `firmware/main/boards/zhengchen-cam/config.json`
- Modify: `firmware/main/**`
- Modify: `firmware/scripts/**`
- Modify: `firmware/tests/**`
- Create: `firmware/releases/zixuan-release-manifest.json`

- [ ] **Step 1: Update failing board and protocol assertions**

Require the Zixuan OTA route, MQTT namespace, application metadata, display name, factory wake word `ni hao zi xuan` / `你好紫萱`, layout 2, and unchanged 8 MiB assets plus two 3 MiB slots.

- [ ] **Step 2: Rename product firmware identity**

Change only product-owned Kconfig labels, build metadata, URLs, topics, UI strings, and generated names. Preserve ESP-IDF symbols, upstream component names, third-party URLs, board hardware values, partitions, and driver identifiers.

- [ ] **Step 3: Build the supported board pair**

Build `zhengchen-cam` from its `config.json`, generate layout-2 assets with the Zixuan factory wake word, and write application/assets SHA-256 values plus partition metadata to the release manifest.

- [ ] **Step 4: Verify firmware without flashing**

Run the full `firmware/tests` suite, inspect the application description and partition table, validate the assets container and both slot boundaries, and scan binaries for obsolete product endpoints.

- [ ] **Step 5: Commit firmware artifacts and metadata**

Commit source, tests, and the manifest. Keep bulky binaries in the existing release artifact location according to repository policy.

### Task 8: Build and rehearse the coherent release

**Files:**
- Create: `deploy/zixuan/nginx.conf`
- Create: `deploy/zixuan/zixuan-manager-api.service`
- Create: `deploy/zixuan/zixuan-server.service`
- Create: `deploy/zixuan/zixuan-mqtt-gateway.service`
- Create: `deploy/zixuan/zixuan-companion-web.service`
- Create: `deploy/zixuan/zixuan-memory.service`
- Create: `deploy/zixuan/release-manifest.json`
- Create: `scripts/verify-zixuan-release.sh`
- Create: `docs/zixuan-cutover-runbook.md`

- [ ] **Step 1: Add deployment manifest tests**

Assert every service, path, image, database, route, topic, and artifact checksum uses the same rename contract version and contains no non-allowlisted retired identity.

- [ ] **Step 2: Create deployment and proxy files**

Define Zixuan systemd services, Nginx routes, environment files, health checks, and explicit old-route rejection. Keep credentials out of tracked files.

- [ ] **Step 3: Build all artifacts**

Run the Java package, Python image, gateway package, console build, web build, firmware application, and assets build. Record immutable checksums in one release manifest.

- [ ] **Step 4: Rehearse install and rollback**

Use a disposable host or isolated namespace. Run migration dry-run, install, service health checks, contract tests, scanner, rollback, and old-stack health checks. Save the sanitized report.

- [ ] **Step 5: Commit release tooling**

Commit deployment files, verifier, runbook, and rehearsal evidence with message `build: prepare coherent zixuan release`.

### Task 9: Production and real-device cutover

**Files:**
- Modify: `openspec/changes/rename-product-to-zixuan/tasks.md`
- Create: `docs/zixuan-release-evidence-2026-09-05.md`
- Regenerate: `docs/ai-plush-companion.architecture.json`
- Regenerate: `docs/ai-plush-companion-architecture.html`

- [ ] **Step 1: Complete the preflight**

Verify all tracked tasks through 8, all checksums, production snapshots, device inventory, maintenance window, rollback package, and current NVS backups. Do not modify production or a device if any item is missing.

- [ ] **Step 2: Freeze and migrate data**

Freeze management writes, take final database, Redis, object/log, proxy, configuration, and artifact snapshots, run migration, and verify the final report before starting new services.

- [ ] **Step 3: Switch the complete service stack**

Install the versioned Zixuan artifacts and proxy configuration together. Verify management login, capabilities, public text/audio, Python runtime, memory, MQTT, OTA, and rejection of obsolete routes/topics.

- [ ] **Step 4: Flash and reactivate each device**

For each inventoried device, verify its MAC and board, save NVS, flash only bootloader, partition table, OTA data, application, and matching assets, then reactivate and bind. Never write a blank-NVS merged image.

- [ ] **Step 5: Complete real-device acceptance**

Verify startup, screen, microphone, speaker, camera, buttons, capability report, wake word, text/audio session, interruption, MQTT reconnect, OTA, and return to service. Record device, board, firmware, assets, Agent version, endpoints, checksums, NVS evidence, and session identifiers.

- [ ] **Step 6: Regenerate architecture and close the change**

Run the final source and artifact scanner, all repository gates, migration parity, service health, and device acceptance. Regenerate the architecture diagram from the Zixuan tree, mark every OpenSpec task complete, validate the change, and archive only when no required evidence is missing.
