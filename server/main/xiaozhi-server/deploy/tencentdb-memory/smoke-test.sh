#!/usr/bin/env bash
set -euo pipefail

: "${MEMORY_CORE_URL:?请设置 MEMORY_CORE_URL}"
: "${MEMORY_CORE_API_KEY:?请设置 MEMORY_CORE_API_KEY}"

SERVICE_ID="${SERVICE_ID:-ai-plush-companion}"
MEMORY_CORE_URL="${MEMORY_CORE_URL%/}"
MODE="create"
if [[ "${1:-}" == "--verify-existing" ]]; then
  MODE="verify"
elif [[ $# -gt 0 ]]; then
  echo "usage: $0 [--verify-existing]" >&2
  exit 2
fi

command -v curl >/dev/null
command -v python3 >/dev/null

SMOKE_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$SMOKE_TMP_DIR"' EXIT

TEAM_ID="ai-plush-companion:user:memory-smoke"
USER_ID="memory-smoke"
AGENT_ID="memory-smoke-profile"
SESSION_ID="memory-smoke-session"
TASK_ID="memory-smoke-device"

request() {
  local path="$1"
  local payload_file="$2"
  local response_file="$3"
  local status
  status="$(curl --silent --show-error \
    --output "$response_file" \
    --write-out "%{http_code}" \
    --request POST \
    --header "Authorization: Bearer ${MEMORY_CORE_API_KEY}" \
    --header "x-tdai-service-id: ${SERVICE_ID}" \
    --header "Content-Type: application/json" \
    --data-binary "@${payload_file}" \
    "${MEMORY_CORE_URL}${path}")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "${path} HTTP ${status}" >&2
    return 1
  fi
}

summary() {
  local response_file="$1"
  local label="$2"
  local collection_key="${3:-}"
  python3 - "$response_file" "$label" "$collection_key" <<'PY'
import json
import sys

path, label, collection_key = sys.argv[1:]
with open(path, encoding="utf-8") as source:
    envelope = json.load(source)
code = envelope.get("code")
if code != 0:
    request_id = envelope.get("request_id") or "missing"
    raise SystemExit(f"{label} envelope code={code} request_id={request_id}")
data = envelope.get("data") or {}
request_id = envelope.get("request_id") or "missing"
if collection_key:
    values = data.get(collection_key) or []
    count = len(values) if isinstance(values, list) else int(data.get("total") or 0)
    print(f"{label} request_id={request_id} count={count}")
else:
    print(f"{label} request_id={request_id}")
PY
}

has_collection() {
  local response_file="$1"
  local collection_key="$2"
  python3 - "$response_file" "$collection_key" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    envelope = json.load(source)
if envelope.get("code") != 0:
    raise SystemExit(2)
data = envelope.get("data") or {}
values = data.get(sys.argv[2]) or []
raise SystemExit(0 if isinstance(values, list) and values else 1)
PY
}

write_isolation_payload() {
  local output_file="$1"
  local extra_json="$2"
  python3 - "$output_file" "$TEAM_ID" "$USER_ID" "$AGENT_ID" "$extra_json" <<'PY'
import json
import sys

output, team_id, user_id, agent_id, extra = sys.argv[1:]
payload = {"team_id": team_id, "user_id": user_id, "agent_id": agent_id}
payload.update(json.loads(extra))
with open(output, "w", encoding="utf-8") as target:
    json.dump(payload, target, ensure_ascii=False)
PY
}

health_status="$(curl --silent --show-error --output /dev/null --write-out "%{http_code}" "${MEMORY_CORE_URL}/health")"
if [[ "$health_status" -lt 200 || "$health_status" -ge 300 ]]; then
  echo "health HTTP ${health_status}" >&2
  exit 1
fi
echo "health status=ok"

payload_file="$SMOKE_TMP_DIR/payload.json"
response_file="$SMOKE_TMP_DIR/response.json"

if [[ "$MODE" == "create" ]]; then
  timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  extra_json="$(python3 - "$SESSION_ID" "$TASK_ID" "$timestamp" <<'PY'
import json
import sys

session_id, task_id, timestamp = sys.argv[1:]
print(json.dumps({
    "session_id": session_id,
    "task_id": task_id,
    "messages": [{"role": "user", "content": f"memory-smoke-{timestamp}", "timestamp": timestamp}],
}))
PY
)"
  write_isolation_payload "$payload_file" "$extra_json"
  request "/v3/conversation/add" "$payload_file" "$response_file"
  summary "$response_file" "conversation_add" "accepted_ids"
fi

write_isolation_payload "$payload_file" "{\"session_id\":\"${SESSION_ID}\",\"task_id\":\"${TASK_ID}\",\"limit\":100,\"offset\":0}"
request "/v3/conversation/query" "$payload_file" "$response_file"
summary "$response_file" "conversation_query" "messages"
if ! has_collection "$response_file" "messages"; then
  echo "conversation_query count=0" >&2
  exit 1
fi

write_isolation_payload "$payload_file" '{"limit":100,"offset":0}'
atomic_ready=0
for _ in $(seq 1 60); do
  request "/v3/atomic/query" "$payload_file" "$response_file"
  if has_collection "$response_file" "items"; then
    atomic_ready=1
    break
  fi
  sleep 2
done
if [[ "$atomic_ready" -ne 1 ]]; then
  echo "atomic_query timeout" >&2
  exit 1
fi
summary "$response_file" "atomic_query" "items"

write_isolation_payload "$payload_file" '{}'
request "/v3/scenario/ls" "$payload_file" "$response_file"
summary "$response_file" "scenario_list" "entries"

if [[ "$MODE" == "create" ]]; then
  write_isolation_payload "$payload_file" '{"content":"memory smoke core"}'
  request "/v3/core/write" "$payload_file" "$response_file"
  summary "$response_file" "core_write"
fi

write_isolation_payload "$payload_file" '{}'
request "/v3/core/read" "$payload_file" "$response_file"
summary "$response_file" "core_read"
