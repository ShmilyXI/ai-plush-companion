#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manager_api="$repo_root/server/main/manager-api"
console="$repo_root/server/main/companion-console"
python_server="$repo_root/server/main/xiaozhi-server"

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ -z "${JAVA_HOME:-}" && -x "$repo_root/.tools/jdk-21.0.12.1+1/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="$repo_root/.tools/jdk-21.0.12.1+1/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

maven_cmd="mvn"
if [[ -x "$repo_root/.tools/apache-maven-3.9.10/bin/mvn" ]]; then
  maven_cmd="$repo_root/.tools/apache-maven-3.9.10/bin/mvn"
fi

python_cmd="python"
if [[ -x "$repo_root/.venv312/bin/python" ]]; then
  python_cmd="$repo_root/.venv312/bin/python"
fi

echo "== manager-api targeted tests =="
(cd "$manager_api" && "$maven_cmd" -q -DskipTests=false -Dtest=AgentSnapshotServiceImplTest,LegacyAgentSkillBindingMigrationServiceTest,CapabilityMigrationAuditServiceImplTest,CompanionMemoryServiceImplTest,CompanionProfileServiceImplTest,DeviceCapabilityServiceImplTest test)
echo "== companion-console checks =="
(cd "$console" && npm run lint && npm run build && npm test -- --run src/app/navigation.test.tsx src/pages/devices/DeviceSkillCard.test.tsx src/pages/devices/DeviceDetailPage.test.tsx src/pages/memories/MemoryPage.test.tsx src/pages/profiles/ProfileEditorPage.test.tsx src/pages/profiles/ProfileModelSettings.test.tsx src/pages/profiles/editor/ProfileCapabilitiesTab.test.tsx && npm run test:e2e)
echo "== Python contracts and migration =="
(cd "$python_server" && "$python_cmd" -m pytest -q tests/test_capability_bundle_client.py tests/test_capability_bundle_cache.py tests/test_companion_memory_management.py tests/test_migration_audit_contract.py tests/test_unify_agent_configuration_acceptance_matrix.py tests/test_unify_agent_configuration_cross_layer.py tests/integration/test_tencentdb_memory_provider_flow.py tests/test_device_skill_end_to_end.py tests/test_connection_tool_routing.py)
echo "== MQTT gateway checks =="
(cd "$repo_root/mqtt-gateway" && "$python_cmd" -m pytest -q)
echo "== simulated rollout dry-run, parity, and rollback =="
(cd "$repo_root" && "$python_cmd" scripts/simulate-unify-agent-rollout.py)
echo "== specification and whitespace gates =="
(cd "$repo_root" && openspec validate --changes --json && git diff --check)
