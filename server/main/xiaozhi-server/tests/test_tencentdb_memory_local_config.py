from pathlib import Path

from ruamel.yaml import YAML


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config.yaml"


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
