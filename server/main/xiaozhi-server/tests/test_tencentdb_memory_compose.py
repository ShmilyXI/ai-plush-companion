from pathlib import Path

import yaml


ROOT = Path(__file__).parents[1]
IMAGE = "agentmemory/memory-core:1.0.0@sha256:f9b286246d0e5020a7f0cb011b7074703d10b76b424a834a117482392f7bd424"


def load(name: str):
    return yaml.safe_load((ROOT / name).read_text(encoding="utf-8"))


def test_base_compose_files_define_private_persistent_memorycore():
    for name in ("docker-compose_all.yml", "docker-compose.yml"):
        compose = load(name)
        service = compose["services"]["tencentdb-memory-core"]
        assert service["image"] == IMAGE
        assert service["restart"] == "unless-stopped"
        assert service["expose"] == ["8420"]
        assert "ports" not in service
        assert service["healthcheck"]["test"] == [
            "CMD-SHELL", "curl -fsS http://127.0.0.1:8420/health >/dev/null || exit 1"
        ]
        assert "tencentdb_memory_data:/data/tdai-memory" in service["volumes"]
        assert any("tdai-gateway.template.yaml:/data/config/tdai-gateway.template.yaml:ro" in item
                   for item in service["volumes"])
        assert any("render-gateway-config.mjs:/data/config/render-gateway-config.mjs:ro" in item
                   for item in service["volumes"])
        assert service["environment"]["TDAI_GATEWAY_CONFIG"] == "/tmp/tdai-gateway.yaml"
        assert "render-gateway-config.mjs" in service["command"]
        for key in (
            "TENCENTDB_MEMORY_MODEL_PROXY_KEY",
            "TENCENTDB_MEMORY_CORE_KEY",
            "TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS",
            "TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL",
        ):
            assert key in service["environment"]
        assert "tencentdb_memory_data" in compose["volumes"]


def test_all_in_one_and_server_only_network_contracts_are_distinct():
    all_in_one = load("docker-compose_all.yml")
    server_only = load("docker-compose.yml")

    assert "xiaozhi-esp32-server-web" in all_in_one["services"]["tencentdb-memory-core"]["depends_on"]
    assert "tencentdb-memory-core" not in all_in_one["services"]["xiaozhi-esp32-server"].get("depends_on", [])
    assert "xiaozhi-esp32-server-web:8002" in all_in_one["services"]["tencentdb-memory-core"]["environment"][
        "TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL"
    ]
    service = server_only["services"]["tencentdb-memory-core"]
    assert "host.docker.internal:8002" in service["environment"]["TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL"]
    assert "host.docker.internal:host-gateway" in service["extra_hosts"]


def test_dev_override_is_the_only_host_port_mapping():
    override = load("docker-compose.tencentdb-memory.dev.yml")
    assert override["services"]["tencentdb-memory-core"]["ports"] == ["127.0.0.1:8420:8420"]


def test_example_environment_documents_separate_keys():
    text = (ROOT / ".env.tencentdb-memory.example").read_text(encoding="utf-8")
    assert "TENCENTDB_MEMORY_CORE_KEY=" in text
    assert "TENCENTDB_MEMORY_MODEL_PROXY_KEY=" in text
    assert "TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS=1024" in text
    assert "xiaozhi-esp32-server-web:8002" in text
    assert "server.secret" in text
    assert "不能相同" in text
