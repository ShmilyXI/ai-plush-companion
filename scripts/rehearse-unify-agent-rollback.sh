#!/usr/bin/env bash
set -euo pipefail

# The manager-api compatibility switch keeps legacy device projection available
# while published agent versions and migrated bindings remain intact.
export SPRING_APPLICATION_JSON='{"companion":{"capability":{"agent-projection-enabled":false}}}'
echo "Rollback rehearsal configuration: companion.capability.agent-projection-enabled=false"
echo "Start manager-api with this environment, check /admin/companion/capabilities/migration-audit,"
echo "then verify the existing MQTT and Python WebSocket health checks before restoring the flag."
