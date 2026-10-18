import hashlib
import json
import os
import subprocess
import uuid
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
MYSQL_SCRIPT = ROOT / "scripts/zixuan-migrate-mysql.sh"
REDIS_SCRIPT = ROOT / "scripts/zixuan-migrate-redis.py"
STORAGE_SCRIPT = ROOT / "scripts/zixuan-migrate-storage.sh"
REPORT_SCRIPT = ROOT / "scripts/zixuan-cutover-report.py"
ROLLBACK_SCRIPT = ROOT / "scripts/zixuan-rollback.sh"


def run(command, *, env=None, check=True):
    return subprocess.run(
        [str(item) for item in command],
        cwd=ROOT,
        env={**os.environ, **(env or {})},
        text=True,
        capture_output=True,
        check=check,
    )


def require_scripts():
    for script in (MYSQL_SCRIPT, REDIS_SCRIPT, STORAGE_SCRIPT, REPORT_SCRIPT, ROLLBACK_SCRIPT):
        assert script.exists(), f"missing migration tool: {script.name}"


@pytest.fixture(scope="module")
def mysql_env():
    password = os.environ.get("ZIXUAN_TEST_MYSQL_PASSWORD")
    if not password:
        pytest.skip("ZIXUAN_TEST_MYSQL_PASSWORD is required")
    return {"MYSQL_PASSWORD": password}


@pytest.fixture
def mysql_databases(mysql_env):
    suffix = uuid.uuid4().hex[:10]
    source = f"zixuan_test_source_{suffix}"
    target = f"zixuan_test_target_{suffix}"
    mysql = ["mysql", "-h127.0.0.1", "-P3309", "-uroot", "-N", "-B"]
    run(mysql + ["-e", f"CREATE DATABASE `{source}` CHARACTER SET utf8mb4"] , env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})
    run(mysql + [source, "-e", "CREATE TABLE sys_user(id BIGINT PRIMARY KEY); INSERT INTO sys_user VALUES (7), (8); CREATE TABLE ai_agent(id VARCHAR(32) PRIMARY KEY, user_id BIGINT); INSERT INTO ai_agent VALUES ('agent-1', 7); CREATE TABLE ai_device(id VARCHAR(32) PRIMARY KEY, user_id BIGINT, agent_id VARCHAR(32)); INSERT INTO ai_device VALUES ('device-1', 7, 'agent-1'); CREATE TABLE DATABASECHANGELOG(ID VARCHAR(64), AUTHOR VARCHAR(64), FILENAME VARCHAR(255), MD5SUM VARCHAR(64)); INSERT INTO DATABASECHANGELOG VALUES ('1','legacy','old.sql','abc123');"], env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})
    yield source, target
    run(mysql + ["-e", f"DROP DATABASE IF EXISTS `{source}`; DROP DATABASE IF EXISTS `{target}`"], env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})


def test_mysql_dry_run_apply_idempotency_and_history(tmp_path, mysql_env, mysql_databases):
    require_scripts()
    source, target = mysql_databases
    common = [
        MYSQL_SCRIPT, "--host", "127.0.0.1", "--port", "3309", "--user", "root",
        "--source", source, "--target", target, "--snapshot-dir", tmp_path / "snapshots",
    ]
    dry_report = tmp_path / "mysql-dry.json"
    run(common + ["--report", dry_report], env=mysql_env)
    assert json.loads(dry_report.read_text())["mode"] == "dry-run"
    exists = run(["mysql", "-h127.0.0.1", "-P3309", "-uroot", "-N", "-B", "-e", f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{target}'"], env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})
    assert exists.stdout.strip() == "0"

    apply_report = tmp_path / "mysql-apply.json"
    run(common + ["--report", apply_report, "--apply"], env=mysql_env)
    first = json.loads(apply_report.read_text())
    assert first["status"] == "ready"
    assert first["sourceTableCounts"] == first["targetTableCounts"]
    assert first["liquibaseDigest"] == hashlib.sha256(b"1\x1flegacy\x1fold.sql\x1fabc123\n").hexdigest()

    second_report = tmp_path / "mysql-second.json"
    run(common + ["--report", second_report, "--apply"], env=mysql_env)
    assert json.loads(second_report.read_text())["status"] == "already-ready"


def test_mysql_detects_changed_business_ownership_with_equal_table_counts(tmp_path, mysql_env, mysql_databases):
    require_scripts()
    source, target = mysql_databases
    common = [
        MYSQL_SCRIPT, "--host", "127.0.0.1", "--port", "3309", "--user", "root",
        "--source", source, "--target", target, "--snapshot-dir", tmp_path / "snapshots",
    ]
    run(common + ["--report", tmp_path / "apply.json", "--apply"], env=mysql_env)
    run(
        ["mysql", "-h127.0.0.1", "-P3309", "-uroot", "-N", "-B", target,
         "-e", "UPDATE ai_device SET user_id = 999 WHERE id = 'device-1'"],
        env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]},
    )

    report_path = tmp_path / "ownership-conflict.json"
    result = run(common + ["--report", report_path, "--apply"], env=mysql_env, check=False)
    report = json.loads(report_path.read_text())

    assert result.returncode != 0
    assert report["status"] == "conflict"
    assert "device-user" in report["failedOwnershipInvariants"]


def test_mysql_injected_failure_keeps_source_and_removes_partial_target(tmp_path, mysql_env, mysql_databases):
    require_scripts()
    source, target = mysql_databases
    result = run([
        MYSQL_SCRIPT, "--host", "127.0.0.1", "--port", "3309", "--user", "root",
        "--source", source, "--target", target, "--snapshot-dir", tmp_path / "snapshots",
        "--report", tmp_path / "failed.json", "--apply", "--inject-failure", "after-import",
    ], env=mysql_env, check=False)
    assert result.returncode != 0
    query = run(["mysql", "-h127.0.0.1", "-P3309", "-uroot", "-N", "-B", source, "-e", "SELECT COUNT(*) FROM ai_device"], env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})
    assert query.stdout.strip() == "1"
    exists = run(["mysql", "-h127.0.0.1", "-P3309", "-uroot", "-N", "-B", "-e", f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{target}'"], env={"MYSQL_PWD": mysql_env["MYSQL_PASSWORD"]})
    assert exists.stdout.strip() == "0"


def test_redis_dry_run_apply_ttl_idempotency_and_conflict(tmp_path):
    require_scripts()
    marker = uuid.uuid4().hex[:10]
    source_prefix = f"cutover:{marker}:"
    target_prefix = f"zixuan:cutover:{marker}:"
    source_key = f"{source_prefix}business:user"
    target_key = f"{target_prefix}business:user"
    redis = ["docker", "exec", "ai-plush-companion-redis", "redis-cli"]
    run(redis + ["SET", source_key, "payload", "PX", "120000"])
    try:
        common = [REDIS_SCRIPT, "--host", "127.0.0.1", "--port", "6382", "--container", "ai-plush-companion-redis", "--source-prefix", source_prefix, "--target-prefix", target_prefix, "--include-prefix", "business:"]
        dry = tmp_path / "redis-dry.json"
        run(common + ["--report", dry])
        assert json.loads(dry.read_text())["mode"] == "dry-run"
        assert run(redis + ["EXISTS", target_key]).stdout.strip() == "0"

        applied = tmp_path / "redis-apply.json"
        run(common + ["--report", applied, "--apply"])
        report = json.loads(applied.read_text())
        assert report["copied"] == 1
        assert report["conflicts"] == []
        assert run(redis + ["GET", target_key]).stdout.strip() == "payload"
        assert int(run(redis + ["PTTL", target_key]).stdout) > 0

        second = tmp_path / "redis-second.json"
        run(common + ["--report", second, "--apply"])
        assert json.loads(second.read_text())["unchanged"] == 1

        run(redis + ["PEXPIRE", target_key, "1000"])
        ttl_conflict_report = tmp_path / "redis-ttl-conflict.json"
        ttl_conflict = run(common + ["--report", ttl_conflict_report, "--apply"], check=False)
        assert ttl_conflict.returncode != 0
        assert json.loads(ttl_conflict_report.read_text())["conflicts"][0]["reason"] == "ttl-mismatch"

        run(redis + ["SET", target_key, "conflict"])
        conflict = run(common + ["--report", tmp_path / "redis-conflict.json", "--apply"], check=False)
        assert conflict.returncode != 0
    finally:
        run(redis + ["DEL", source_key, target_key])


def test_redis_allows_unprefixed_source_with_explicit_business_prefixes(tmp_path):
    require_scripts()
    marker = uuid.uuid4().hex[:10]
    source_key = f"business:{marker}:user"
    target_key = f"zixuan:business:{marker}:user"
    redis = ["docker", "exec", "ai-plush-companion-redis", "redis-cli"]
    run(redis + ["SET", source_key, "payload"])
    try:
        report = tmp_path / "redis-unprefixed.json"
        run([
            REDIS_SCRIPT, "--host", "127.0.0.1", "--port", "6382",
            "--container", "ai-plush-companion-redis", "--source-prefix", "",
            "--target-prefix", "zixuan:", "--include-prefix", f"business:{marker}:",
            "--report", report, "--apply",
        ])
        assert run(redis + ["GET", target_key]).stdout.strip() == "payload"
    finally:
        run(redis + ["DEL", source_key, target_key])


def test_storage_copy_preserves_snapshot_and_checksums(tmp_path):
    require_scripts()
    source = tmp_path / "source"
    target = tmp_path / "target"
    snapshots = tmp_path / "snapshots"
    source.mkdir()
    (source / "object.bin").write_bytes(b"object-data")
    (source / "runtime.log").write_text("log-data\n")

    dry = tmp_path / "storage-dry.json"
    run([STORAGE_SCRIPT, "--source-root", source, "--target-root", target, "--snapshot-root", snapshots, "--report", dry])
    assert not target.exists()
    assert json.loads(dry.read_text())["source"]["fileCount"] == 2

    applied = tmp_path / "storage-apply.json"
    run([STORAGE_SCRIPT, "--source-root", source, "--target-root", target, "--snapshot-root", snapshots, "--report", applied, "--apply"])
    report = json.loads(applied.read_text())
    assert report["source"]["digest"] == report["target"]["digest"] == report["snapshot"]["digest"]
    assert (source / "object.bin").read_bytes() == b"object-data"


def test_cutover_report_and_rollback_manifest_require_complete_checksums(tmp_path):
    require_scripts()
    component = tmp_path / "component.json"
    component.write_text(json.dumps({"contractVersion": "zixuan-cutover-v1", "status": "ready"}))
    aggregate = tmp_path / "aggregate.json"
    run([REPORT_SCRIPT, "--contract-version", "zixuan-cutover-v1", "--output", aggregate, component])
    assert json.loads(aggregate.read_text())["status"] == "ready"

    manifest = tmp_path / "release.json"
    manifest.write_text(json.dumps({"contractVersion": "zixuan-cutover-v1", "rollback": {}}))
    rejected = run([ROLLBACK_SCRIPT, "--manifest", manifest], check=False)
    assert rejected.returncode != 0

    rollback = {}
    for name in ("database", "redis", "storage", "proxy", "services", "artifacts"):
        artifact = tmp_path / f"{name}.snapshot"
        artifact.write_text(name)
        rollback[name] = {
            "path": str(artifact),
            "sha256": hashlib.sha256(name.encode()).hexdigest(),
            "command": ["true"],
        }
    manifest.write_text(json.dumps({"contractVersion": "zixuan-cutover-v1", "rollback": rollback}))
    accepted = run([ROLLBACK_SCRIPT, "--manifest", manifest])
    assert len(json.loads(accepted.stdout)["plan"]) == 6

    component.write_text(json.dumps({"contractVersion": "other-version", "status": "ready"}))
    mismatch = run([REPORT_SCRIPT, "--contract-version", "zixuan-cutover-v1", "--output", aggregate, component], check=False)
    assert mismatch.returncode != 0
