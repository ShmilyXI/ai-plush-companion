# Release Evidence

Fill this record before enabling agent-based capability projection.

```text
Commit SHA:
Branch:
Feature flag / compatibility version:
Migration dry-run report:
Conflict disposition:
Acceptance matrix result:
Manager-api command and result:
Console lint, build, test commands and results:
Python command and result:
MQTT gateway command and result:
Visual regression result and artifact:
Accessibility result and artifact:
Parity check result:
Rollback command:
  `SPRING_APPLICATION_JSON='{"companion":{"capability":{"agent-projection-enabled":false}}}'` before starting manager-api
Post-rollback MQTT health:
Post-rollback Python WebSocket health:
Sign-off owner:
```

Latest local simulated verification on `codex/unify-agent-configuration` has
passed the repository gate: manager-api targeted tests, console lint/build and
127 focused tests, Playwright 15 passed with 1 intentionally skipped narrow Agent
editor scenario, Python 74 passed, MQTT gateway 6 passed, OpenSpec validation,
and `git diff --check`. The MQTT and device portions are mocked tests only and
do not constitute hardware or broker evidence. Visual baselines are stored in
`companion-console/e2e/login-quality.spec.ts-snapshots/`; the migration flow
now has desktop and 390px baselines, and the Agent editor has capability and
publish baselines at desktop width.

The current simulated rollout rehearsal is `scripts/simulate-unify-agent-rollout.py`.
It reports two agents with no missing initial versions, three of three legacy
bindings projected, zero conflicts, zero skipped rows, parity success for both
devices, and a rollback that restores the legacy projection while returning
simulated MQTT and Python WebSocket health checks. The acceptance matrix and
race/non-mutation matrix are exercised by
`tests/test_unify_agent_configuration_acceptance_matrix.py` and
`tests/test_unify_agent_configuration_cross_layer.py`. These results are
simulation evidence only; no real data enablement or hardware health check was
performed.
