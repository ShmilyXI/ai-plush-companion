#!/usr/bin/env python3
"""Run the agent-projection rollout gates against deterministic simulated data.

This is a contract rehearsal only. It does not inspect or mutate a live
database, MQTT broker, device, or manager-api process.
"""

from __future__ import annotations

import json
from copy import deepcopy


def main() -> int:
    report = {
        "mode": "simulated",
        "totalAgents": 2,
        "agentsMissingInitialPublishedVersion": 0,
        "legacyBindings": 3,
        "projectedLegacyBindings": 3,
        "conflicts": [],
        "skippedRows": 0,
        "retryableFailures": 0,
    }
    if report["agentsMissingInitialPublishedVersion"] or report["conflicts"] or report["skippedRows"] or report["retryableFailures"]:
        raise SystemExit(json.dumps({"ready": False, "report": report}, ensure_ascii=False))

    legacy = {
        "device-a": {"skills": ["weather"], "tools": ["get_weather"]},
        "device-b": {"skills": ["weather"], "tools": ["get_weather", "camera"]},
    }
    projected = deepcopy(legacy)
    parity = {device: legacy[device] == projected[device] for device in legacy}
    flag_before = False
    flag_after = True
    rollback_projection = deepcopy(projected)
    rollback_flag = flag_before
    result = {
        "ready": all(parity.values()),
        "report": report,
        "parity": {"ready": all(parity.values()), "devices": parity},
        "featureFlag": {"before": flag_before, "after": flag_after},
        "rollback": {"flag": rollback_flag, "projectionRestored": rollback_projection == legacy},
        "healthChecks": {"mqtt": "simulated-pass", "pythonWebSocket": "simulated-pass"},
    }
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if result["ready"] and result["rollback"]["projectionRestored"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
