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

Latest local verification on `beta` has passed the repository gate: manager-api
targeted tests, console lint/build and 615 console tests, Playwright 16 passed,
Python 74 passed, MQTT gateway 6 passed, OpenSpec validation, and
`git diff --check`. The repository MQTT/device tests are supplemented by a real
ESP32-S3 MQTT/WebSocket smoke check documented in the progress record. Visual baselines are stored in
`companion-console/e2e/login-quality.spec.ts-snapshots/`; the migration flow
now has desktop and 390px baselines, and the Agent editor has capability and
publish baselines at desktop and 390px widths.

The current simulated rollout rehearsal is `scripts/simulate-unify-agent-rollout.py`.
It reports two agents with no missing initial versions, three of three legacy
bindings projected, zero conflicts, zero skipped rows, parity success for both
devices, and a rollback that restores the legacy projection while returning
simulated MQTT and Python WebSocket health checks. The acceptance matrix and
race/non-mutation matrix are exercised by
`tests/test_unify_agent_configuration_acceptance_matrix.py` and
`tests/test_unify_agent_configuration_cross_layer.py`. The real device smoke
check is hardware evidence, but the migration report, real-data parity,
memory-provider flow, and post-rollback health checks still require an
authenticated deployment environment. These results must not be treated as
completed release sign-off.
