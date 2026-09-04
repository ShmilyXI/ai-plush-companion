#!/usr/bin/env bash
set -euo pipefail

host=""
port=""
user=""
source_db=""
target_db=""
snapshot_dir=""
report=""
apply=0
inject_failure=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) host=$2; shift 2 ;;
    --port) port=$2; shift 2 ;;
    --user) user=$2; shift 2 ;;
    --source) source_db=$2; shift 2 ;;
    --target) target_db=$2; shift 2 ;;
    --snapshot-dir) snapshot_dir=$2; shift 2 ;;
    --report) report=$2; shift 2 ;;
    --apply) apply=1; shift ;;
    --inject-failure) inject_failure=$2; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 64 ;;
  esac
done

for value in "$host" "$port" "$user" "$source_db" "$target_db" "$snapshot_dir" "$report"; do
  [[ -n "$value" ]] || { printf 'All connection, database, snapshot, and report arguments are required\n' >&2; exit 64; }
done
[[ -n "${MYSQL_PASSWORD:-}" ]] || { printf 'MYSQL_PASSWORD is required\n' >&2; exit 64; }
[[ "$source_db" =~ ^[A-Za-z0-9_]+$ && "$target_db" =~ ^[A-Za-z0-9_]+$ ]] || { printf 'Unsafe database name\n' >&2; exit 64; }
[[ "$source_db" != "$target_db" ]] || { printf 'Source and target databases must differ\n' >&2; exit 64; }

export MYSQL_PWD="$MYSQL_PASSWORD"
mysql_args=(-h "$host" -P "$port" -u "$user" -N -B)
mkdir -p "$snapshot_dir" "$(dirname "$report")"
dump_file="$snapshot_dir/${source_db}.sql"

database_exists() {
  [[ "$(mysql "${mysql_args[@]}" -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$1'")" == "1" ]]
}

table_counts() {
  local database=$1 output=$2
  : > "$output"
  while IFS= read -r table; do
    [[ -n "$table" ]] || continue
    local count
    count=$(mysql "${mysql_args[@]}" "$database" -e "SELECT COUNT(*) FROM \`$table\`")
    printf '%s\t%s\n' "$table" "$count" >> "$output"
  done < <(mysql "${mysql_args[@]}" -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$database' AND table_type='BASE TABLE' ORDER BY table_name")
}

liquibase_digest() {
  local database=$1
  local present
  present=$(mysql "${mysql_args[@]}" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND UPPER(table_name)='DATABASECHANGELOG'")
  if [[ "$present" != "1" ]]; then
    printf ''
    return
  fi
  mysql "${mysql_args[@]}" "$database" -e "SELECT CONCAT_WS(CHAR(31), ID, AUTHOR, FILENAME, COALESCE(MD5SUM,'')) FROM DATABASECHANGELOG ORDER BY ID, AUTHOR, FILENAME" | shasum -a 256 | awk '{print $1}'
}

write_report() {
  local mode=$1 status=$2 source_counts=$3 target_counts=$4 digest=$5
  python3 - "$report" "$mode" "$status" "$source_db" "$target_db" "$source_counts" "$target_counts" "$digest" "$dump_file" <<'PY'
import json, sys
from pathlib import Path

def counts(path):
    if not path or not Path(path).exists(): return {}
    return {line.split("\t", 1)[0]: int(line.split("\t", 1)[1]) for line in Path(path).read_text().splitlines() if line}

output, mode, status, source, target, source_counts, target_counts, digest, snapshot = sys.argv[1:]
payload = {
    "contractVersion": "zixuan-cutover-v1", "mode": mode, "status": status,
    "sourceDatabase": source, "targetDatabase": target,
    "sourceTableCounts": counts(source_counts), "targetTableCounts": counts(target_counts),
    "liquibaseDigest": digest, "snapshot": str(Path(snapshot).resolve()),
}
Path(output).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
PY
}

database_exists "$source_db" || { printf 'Source database does not exist\n' >&2; exit 66; }
source_counts=$(mktemp)
target_counts=$(mktemp)
trap 'unlink "$source_counts" 2>/dev/null || true; unlink "$target_counts" 2>/dev/null || true' EXIT
table_counts "$source_db" "$source_counts"
source_digest=$(liquibase_digest "$source_db")
mysqldump -h "$host" -P "$port" -u "$user" --single-transaction --routines --triggers --events --hex-blob --set-gtid-purged=OFF "$source_db" > "$dump_file"

if [[ "$apply" -eq 0 ]]; then
  write_report dry-run planned "$source_counts" "" "$source_digest"
  exit 0
fi

if database_exists "$target_db"; then
  table_counts "$target_db" "$target_counts"
  target_digest=$(liquibase_digest "$target_db")
  if cmp -s "$source_counts" "$target_counts" && [[ "$source_digest" == "$target_digest" ]]; then
    write_report apply already-ready "$source_counts" "$target_counts" "$source_digest"
    exit 0
  fi
  write_report apply conflict "$source_counts" "$target_counts" "$source_digest"
  exit 2
fi

mysql "${mysql_args[@]}" -e "CREATE DATABASE \`$target_db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
if ! mysql "${mysql_args[@]}" "$target_db" < "$dump_file"; then
  mysql "${mysql_args[@]}" -e "DROP DATABASE IF EXISTS \`$target_db\`"
  exit 1
fi
if [[ "$inject_failure" == "after-import" ]]; then
  mysql "${mysql_args[@]}" -e "DROP DATABASE IF EXISTS \`$target_db\`"
  write_report apply failed "$source_counts" "" "$source_digest"
  exit 97
fi
table_counts "$target_db" "$target_counts"
target_digest=$(liquibase_digest "$target_db")
if ! cmp -s "$source_counts" "$target_counts" || [[ "$source_digest" != "$target_digest" ]]; then
  mysql "${mysql_args[@]}" -e "DROP DATABASE IF EXISTS \`$target_db\`"
  write_report apply mismatch "$source_counts" "$target_counts" "$source_digest"
  exit 2
fi
write_report apply ready "$source_counts" "$target_counts" "$source_digest"
