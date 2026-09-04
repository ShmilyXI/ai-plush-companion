import json
from pathlib import Path


FIXTURE = Path(__file__).parent / "fixtures" / "tencentdb_memory_v3_contract.json"


def test_supported_memorycore_contract_is_complete_and_pinned():
    contract = json.loads(FIXTURE.read_text(encoding="utf-8"))

    assert contract["release"] == "v2.0.0"
    assert contract["image"].startswith("agentmemory/memory-core:1.0.0@sha256:")
    assert len(contract["image"].rsplit("sha256:", 1)[1]) == 64
    assert contract["service_id"] == "ai-plush-companion"

    expected_methods = {
        "conversation_add",
        "conversation_query",
        "conversation_delete",
        "atomic_query",
        "atomic_search",
        "atomic_update",
        "atomic_delete",
        "scenario_list",
        "scenario_remove",
        "core_read",
        "core_write",
    }
    assert set(contract["routes"]) == expected_methods
    assert all(route.startswith("/v3/") for route in contract["routes"].values())
    assert contract["collections"] == {
        "conversation_query": "messages",
        "atomic_query": "items",
        "atomic_search": "items",
        "scenario_list": "entries",
    }
    assert contract["l2_l3_scope"] == ["team_id", "agent_id"]
    assert "user_id" not in contract["l2_l3_scope"]
