from pathlib import Path

from ruamel.yaml import YAML


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config.yaml"
REPOSITORY = Path(__file__).resolve().parents[4]


def test_local_config_keeps_memory_disabled_and_documents_tencentdb_example():
    raw = CONFIG.read_text(encoding="utf-8")
    config = YAML(typ="safe").load(raw)

    assert config["selected_module"]["Memory"] == "nomem"
    assert config["Memory"]["tencentdb"] == {
        "type": "tencentdb",
        "memory_core_url": "http://127.0.0.1:8420",
        "memory_core_api_key": "",
        "request_timeout_seconds": 4,
    }
    assert "deploy/tencentdb-memory/UPSTREAM.md" in raw
    assert "不使用 manager-api" in raw
    for field in ("user_id", "agent_id", "device_id", "memory_namespace"):
        assert f"companion_identity.{field}" in raw


def test_local_memory_environment_is_ignored_and_configurable():
    assert "server/main/zixuan-server/.env.tencentdb-memory" in (
        REPOSITORY / ".gitignore"
    ).read_text(encoding="utf-8")
    script = (ROOT / "deploy/tencentdb-memory/configure-local.sh").read_text(encoding="utf-8")
    assert "TENCENTDB_MEMORY_CORE_KEY" in script
    assert "server.secret" in script
    assert "docker compose" in script
    assert "http://host.docker.internal:8002/zixuan/internal/tencentdb-memory-model/v1" in script
