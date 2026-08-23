#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manager_api="$repo_root/server/main/manager-api"
python_server="$repo_root/server/main/xiaozhi-server"
firmware="$repo_root/firmware"

if [[ -x "$repo_root/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="$repo_root/.codex-tmp/jdk21-download/jdk-21.0.12+8/Contents/Home"
fi
export PATH="$JAVA_HOME/bin:$PATH"

python_cmd="python3"
if [[ -x "$repo_root/.venv312/bin/python" ]]; then
  python_cmd="$repo_root/.venv312/bin/python"
fi

echo "== manager-api public conversation tests =="
(cd "$manager_api" && mvn -q -Dtest='xiaozhi.modules.conversation.**' test)

echo "== Python public conversation tests =="
(cd "$python_server" && "$python_cmd" -m pytest -q tests/test_public_conversation_*.py tests/test_public_conversation_history.py)

echo "== firmware board configuration tests =="
(cd "$firmware" && "$python_cmd" -m pytest -q tests/test_bread_compact_wifi_s3cam_config.py tests/test_bread_s3cam_headless.py tests/test_zhengchen_camera_capability.py)

echo "== repository whitespace check =="
(cd "$repo_root" && git diff --check)

echo "silent verification passed"
