from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs" / "tencentdb-agent-memory.md"
SMOKE = ROOT / "deploy" / "tencentdb-memory" / "smoke-test.sh"


def test_operations_guide_covers_start_stop_backup_restore_upgrade_and_purge():
    guide = GUIDE.read_text(encoding="utf-8")

    required_commands = (
        "docker compose -f docker-compose_all.yml up -d tencentdb-memory-core",
        "docker compose -f docker-compose_all.yml -f docker-compose.tencentdb-memory.dev.yml up -d tencentdb-memory-core",
        "docker compose -f docker-compose_all.yml stop tencentdb-memory-core",
        'docker run --rm -v xiaozhi-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar czf /backup/tencentdb-memory-backup.tgz -C /data .',
        'docker run --rm -v xiaozhi-server_tencentdb_memory_data:/data -v "$PWD":/backup alpine tar xzf /backup/tencentdb-memory-backup.tgz -C /data',
        "docker volume rm xiaozhi-server_tencentdb_memory_data",
    )
    for command in required_commands:
        assert command in guide

    assert "删除不可恢复" in guide
    assert "删除前必须停止 MemoryCore" in guide
    assert "备份" in guide
    assert "镜像摘要" in guide
    assert "契约测试" in guide
    assert "冒烟测试" in guide
    assert "恢复上一版镜像摘要和数据卷备份" in guide
    assert "只重启 MemoryCore" in guide
    assert "向量重新索引" in guide
    assert "地址、密钥或模型名修改后，下一次请求立即生效" in guide
    assert "bash deploy/tencentdb-memory/configure-local.sh" in guide
    assert "http://host.docker.internal:8420" in guide
    assert "http://tencentdb-memory-core:8420" in guide


def test_smoke_script_is_checked_in_and_never_echoes_credentials_or_content():
    script = SMOKE.read_text(encoding="utf-8")

    assert "MEMORY_CORE_URL" in script
    assert "MEMORY_CORE_API_KEY" in script
    assert "SERVICE_ID" in script
    assert "--verify-existing" in script
    assert "Authorization: Bearer ${MEMORY_CORE_API_KEY}" in script
    assert 'printf "%s" "$MEMORY_CORE_API_KEY"' not in script
    assert "likes" not in script
