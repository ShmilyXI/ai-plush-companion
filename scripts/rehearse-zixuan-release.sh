#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo_root/.codex-tmp"
rehearsal_root="$(mktemp -d "$repo_root/.codex-tmp/zixuan-rehearsal.XXXXXX")"
trap 'rm -rf "$rehearsal_root"' EXIT

cd "$repo_root"

scripts/verify-zixuan-release.sh

install_root="$rehearsal_root/host/opt/zixuan"
mkdir -p "$install_root/artifacts" "$install_root/deploy"
cp output/zixuan-release-2.2.7/* "$install_root/artifacts/"
cp -R deploy/zixuan/. "$install_root/deploy/"

for artifact in output/zixuan-release-2.2.7/*; do
  cmp "$artifact" "$install_root/artifacts/$(basename "$artifact")"
done

storage_source="$rehearsal_root/storage-source"
storage_target="$rehearsal_root/storage-target"
storage_snapshots="$rehearsal_root/storage-snapshots"
mkdir -p "$storage_source"
printf 'zixuan-rehearsal-object\n' > "$storage_source/object.txt"
scripts/zixuan-migrate-storage.sh \
  --source-root "$storage_source" \
  --target-root "$storage_target" \
  --snapshot-root "$storage_snapshots" \
  --report "$rehearsal_root/storage-dry-run.json"
scripts/zixuan-migrate-storage.sh \
  --source-root "$storage_source" \
  --target-root "$storage_target" \
  --snapshot-root "$storage_snapshots" \
  --report "$rehearsal_root/storage-apply.json" \
  --apply
cmp "$storage_source/object.txt" "$storage_target/object.txt"

rollback_root="$rehearsal_root/rollback"
mkdir -p "$rollback_root/snapshots" "$rollback_root/restored"
node --input-type=module - "$rollback_root" <<'NODE'
import { createHash } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const root = process.argv[2]
const components = ['database', 'redis', 'storage', 'proxy', 'services', 'artifacts']
const rollback = {}
for (const component of components) {
  const path = join(root, 'snapshots', `${component}.snapshot`)
  const destination = join(root, 'restored', `${component}.snapshot`)
  const content = `${component}\n`
  writeFileSync(path, content)
  rollback[component] = {
    path,
    sha256: createHash('sha256').update(content).digest('hex'),
    command: ['cp', path, destination],
  }
}
writeFileSync(join(root, 'manifest.json'), `${JSON.stringify({ contractVersion: 'zixuan-cutover-v1', rollback }, null, 2)}\n`)
NODE
scripts/zixuan-rollback.sh --manifest "$rollback_root/manifest.json" --apply >/dev/null
for snapshot in "$rollback_root"/snapshots/*; do
  cmp "$snapshot" "$rollback_root/restored/$(basename "$snapshot")"
done

workspace_jdk="$repo_root/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home"
if [[ -x "$workspace_jdk/bin/java" ]]; then
  export JAVA_HOME="$workspace_jdk"
elif [[ "$(java -version 2>&1 | head -n 1)" != *'"21.'* ]]; then
  echo "Java 21 is required for the release rehearsal" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
[[ "$(java -version 2>&1 | head -n 1)" == *'"21.'* ]]
mvn -q -DskipTests=false \
  -Dtest=OTAControllerHeartbeatTest,PublicConversationControllerTest \
  -f server/main/manager-api/pom.xml test

.venv312/bin/python -m pytest -q \
  server/main/zixuan-server/tests/test_zixuan_route_contract.py \
  server/main/zixuan-server/tests/test_public_conversation_http.py \
  server/main/zixuan-server/tests/test_tencentdb_memory_client.py
.venv312/bin/python -m pytest -q mqtt-gateway/tests
npm --prefix server/main/companion-web test -- --reporter=dot

node --input-type=module - "deploy/zixuan/rehearsal-report.json" "$(git rev-parse HEAD)" <<'NODE'
import { writeFileSync } from 'node:fs'

const output = process.argv[2]
const sourceRevision = process.argv[3]
const checks = [
  'artifact-install',
  'brand-scan',
  'manager-api',
  'memory',
  'mqtt',
  'nginx',
  'ota',
  'public-session',
  'python-runtime',
  'rollback',
  'storage-migration',
]
writeFileSync(output, `${JSON.stringify({
  contractVersion: 'zixuan-cutover-v1',
  sourceRevision,
  status: 'passed',
  sanitized: true,
  environment: 'disposable-filesystem-and-local-test-runtimes',
  checks: checks.map((name) => ({ name, status: 'passed' })),
}, null, 2)}\n`)
NODE

echo "Zixuan release rehearsal passed"
