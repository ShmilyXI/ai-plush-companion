import json
from pathlib import Path


def test_migration_audit_fixture_has_stable_non_secret_shape():
    fixture = json.loads((Path(__file__).parent / "fixtures/migration_audit.json").read_text())
    assert set(fixture) == {
        "id", "agentId", "sourceDeviceId", "targetDeviceId", "mode",
        "sourceCount", "targetCount", "importedCount", "skippedCount",
        "outcome", "retryable", "recovered",
    }
    assert fixture["mode"] in {"merge", "overwrite"}
    assert fixture["outcome"] in {"SUCCEEDED", "FAILED", "RUNNING"}
    assert all(isinstance(fixture[key], int) and fixture[key] >= 0 for key in (
        "sourceCount", "targetCount", "importedCount", "skippedCount"))
    assert "secret" not in json.dumps(fixture).lower()
