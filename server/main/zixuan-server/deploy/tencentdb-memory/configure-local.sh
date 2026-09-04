#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
server_root="$(cd "$script_dir/../.." && pwd)"
env_file="$server_root/.env.tencentdb-memory"

memory_mysql_container="${MEMORY_MYSQL_CONTAINER:-ai-plush-companion-mysql}"
memory_mysql_password="${MEMORY_MYSQL_ROOT_PASSWORD:-123456}"
memory_mysql_database="${MEMORY_MYSQL_DATABASE:-zixuan_esp32_server}"

command -v docker >/dev/null
command -v openssl >/dev/null

memory_proxy_key="$(docker exec -e MYSQL_PWD="$memory_mysql_password" "$memory_mysql_container" \
  mysql -uroot -D "$memory_mysql_database" -N -B -e \
  "SELECT param_value FROM sys_params WHERE param_code='server.secret' LIMIT 1" | tr -d '\r\n')"
if [[ -z "$memory_proxy_key" ]]; then
  echo "未读取到后台参数 server.secret" >&2
  exit 1
fi

memory_core_key=""
if [[ -f "$env_file" ]]; then
  memory_core_key="$(sed -n 's/^TENCENTDB_MEMORY_CORE_KEY=//p' "$env_file" | head -n 1)"
fi
if [[ -z "$memory_core_key" || "$memory_core_key" == "$memory_proxy_key" ]]; then
  memory_core_key="$(openssl rand -hex 32)"
fi

umask 077
env_tmp="$(mktemp "${env_file}.tmp.XXXXXX")"
trap 'rm -f "$env_tmp"' EXIT
{
  printf 'TENCENTDB_MEMORY_CORE_KEY=%s\n' "$memory_core_key"
  printf 'TENCENTDB_MEMORY_MODEL_PROXY_KEY=%s\n' "$memory_proxy_key"
  printf 'TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS=1024\n'
  printf 'TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL=http://host.docker.internal:8002/zixuan/internal/tencentdb-memory-model/v1\n'
} > "$env_tmp"
chmod 600 "$env_tmp"
mv "$env_tmp" "$env_file"
trap - EXIT

docker compose --env-file "$env_file" \
  -f "$server_root/docker-compose.yml" \
  -f "$server_root/docker-compose.tencentdb-memory.dev.yml" \
  up -d tencentdb-memory-core

echo "MemoryCore 已启动，环境文件已保存到 .env.tencentdb-memory，密钥未输出"
echo "本机健康检查地址 http://127.0.0.1:8420/health"
