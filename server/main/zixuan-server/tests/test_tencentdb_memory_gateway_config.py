import os
import subprocess
from pathlib import Path

import pytest
import yaml


ROOT = Path(__file__).parents[1]
DEPLOY = ROOT / "deploy" / "tencentdb-memory"


def render(tmp_path: Path, dimensions: str = "1024") -> subprocess.CompletedProcess[str]:
    output = tmp_path / "tdai-gateway.yaml"
    env = os.environ.copy()
    env.update({
        "TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS": dimensions,
        "TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL":
            "http://zixuan-manager-web:8002/zixuan/internal/tencentdb-memory-model/v1",
    })
    return subprocess.run(
        [
            "node",
            str(DEPLOY / "render-gateway-config.mjs"),
            str(DEPLOY / "tdai-gateway.template.yaml"),
            str(output),
        ],
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def test_rendered_gateway_config_has_the_approved_standalone_contract(tmp_path):
    result = render(tmp_path)
    assert result.returncode == 0, result.stderr
    config = yaml.safe_load((tmp_path / "tdai-gateway.yaml").read_text(encoding="utf-8"))

    assert config["deployMode"] == "standalone"
    assert config["stateBackend"] == "local"
    assert config["server"] == {
        "host": "0.0.0.0", "port": 8420, "apiKey": "${TENCENTDB_MEMORY_CORE_KEY}"
    }
    assert config["data"]["baseDir"] == "/data/tdai-memory"
    proxy = "http://zixuan-manager-web:8002/zixuan/internal/tencentdb-memory-model/v1"
    assert config["llm"]["baseUrl"] == proxy
    assert config["llm"]["apiKey"] == "${TENCENTDB_MEMORY_MODEL_PROXY_KEY}"
    assert config["llm"]["model"] == "memory-llm-proxy"
    assert config["memory"]["promptMode"] == "chat"
    assert config["memory"]["capture"]["enabled"] is True
    assert config["memory"]["extraction"]["enabled"] is True
    assert config["memory"]["extraction"]["enableDedup"] is True
    assert config["memory"]["persona"]["triggerEveryN"] == 20
    assert config["memory"]["pipeline"]["everyNConversations"] == 1
    assert config["memory"]["pipeline"]["enableWarmup"] is True
    assert config["memory"]["recall"]["strategy"] == "hybrid"
    assert config["memory"]["storeBackend"] == "sqlite"
    assert config["memory"]["embedding"]["provider"] == "openai"
    assert config["memory"]["embedding"]["baseUrl"] == proxy
    assert config["memory"]["embedding"]["dimensions"] == 1024
    assert isinstance(config["memory"]["embedding"]["dimensions"], int)
    assert config["memory"]["embedding"]["sendDimensions"] is True
    assert config["memory"]["bm25"] == {"enabled": True, "language": "zh"}
    assert config["skill"]["enabled"] is False
    assert config["observability"]["metrics"]["enabled"] is False
    assert config["observability"]["tracing"]["enabled"] is False


@pytest.mark.parametrize("dimensions", ["", "0", "-1", "1.5", "abc"])
def test_renderer_rejects_invalid_dimensions_without_writing_output(tmp_path, dimensions):
    result = render(tmp_path, dimensions)

    assert result.returncode != 0
    assert not (tmp_path / "tdai-gateway.yaml").exists()
