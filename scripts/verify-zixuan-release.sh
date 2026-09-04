#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repo_root/deploy/zixuan/release-manifest.json"
firmware_manifest="$repo_root/firmware/releases/zixuan-release-manifest.json"

cd "$repo_root"

test -f "$firmware_manifest"

node scripts/verify-zixuan-brand.mjs
if [[ -f deploy/zixuan/rehearsal-report.json ]]; then
  node --test scripts/verify-zixuan-firmware.test.mjs scripts/verify-zixuan-release.test.mjs
else
  node --test \
    --test-name-pattern='^(maintained boards|firmware source|firmware release|zixuan deployment|zixuan proxy|container and release|mqtt release)' \
    scripts/verify-zixuan-firmware.test.mjs scripts/verify-zixuan-release.test.mjs
fi

node --input-type=module - "$manifest" <<'NODE'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync, statSync } from 'node:fs'

const manifestPath = process.argv[2]
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
assert.equal(manifest.contractVersion, 'zixuan-cutover-v1')
assert.equal(manifest.status, 'ready')

for (const artifact of manifest.artifacts) {
  assert.equal(artifact.contractVersion, manifest.contractVersion)
  if (artifact.kind === 'file') {
    const data = readFileSync(artifact.path)
    assert.equal(statSync(artifact.path).size, artifact.size, `${artifact.name} size`)
    assert.equal(createHash('sha256').update(data).digest('hex'), artifact.sha256, `${artifact.name} sha256`)
  } else if (artifact.kind === 'docker-image') {
    const imageId = execFileSync('docker', ['image', 'inspect', artifact.image, '--format', '{{.Id}}'], { encoding: 'utf8' }).trim()
    assert.equal(imageId, `sha256:${artifact.sha256}`, `${artifact.name} image id`)
  } else {
    assert.fail(`unsupported artifact kind: ${artifact.kind}`)
  }
}
NODE

SPRING_DATASOURCE_DRUID_PASSWORD=verification-only \
ZIXUAN_MYSQL_ROOT_PASSWORD=verification-only \
  docker compose -f deploy/zixuan/docker-compose.yml config --quiet

docker run --rm \
  -v "$repo_root/deploy/zixuan/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t

if jar tf output/zixuan-release-2.2.7/zixuan-manager-api.jar | grep -q '^xiaozhi/'; then
  echo "retired Java package found in manager artifact" >&2
  exit 1
fi

for archive in \
  output/zixuan-release-2.2.7/zixuan-companion-console.tgz \
  output/zixuan-release-2.2.7/zixuan-companion-web.tgz \
  output/zixuan-release-2.2.7/zixuan-mqtt-gateway.tgz; do
  if tar -xOzf "$archive" 2>/dev/null | strings | grep -Eiq '/xiaozhi/(v1|ota|internal)|xiaozhi_esp32_server|server/main/xiaozhi-server'; then
    echo "retired product contract found in $archive" >&2
    exit 1
  fi
done

for firmware in firmware/releases/zixuan-v2.2.7/*/zixuan.bin firmware/releases/zixuan-v2.2.7/*/generated_assets.bin; do
  if LC_ALL=C grep -aEiq '/xiaozhi/(v1|ota|internal)|ni hao xiao zhi|你好小智' "$firmware"; then
    echo "retired product contract found in $firmware" >&2
    exit 1
  fi
done

echo "Zixuan release verification passed"
