# Companion Console Quality Acceptance

The console uses the existing Ant Design 5 components and project tokens. New
agent, device capability, and migration screens must keep the existing page
container, surface card, form, notification, and responsive grid primitives.

Every affected view is checked in these states: loading, empty, request error,
disabled action, focused control, validation error, unsaved changes, and narrow
viewport. Text must remain readable without horizontal clipping, controls must
have an accessible name, and read-only projections must not render mutation
controls.

The Agent model tab can open the owner-scoped global credential editor for a
missing model. It submits only newly entered secret fields, never renders
stored secret values, and refreshes the model option status after a successful
save.

The executable baseline currently includes:

```text
npm run lint
npm run build
npm test -- --run src/pages/devices/DeviceSkillCard.test.tsx src/pages/devices/DeviceDetailPage.test.tsx
npm run test:e2e
```

The device projection tests assert the read-only ownership boundary, successful
and failed loading states, unavailable capability reasons, and preservation of
device controls. The Playwright quality gate runs the real Vite app at desktop
and 390px widths and verifies semantic fields, keyboard focus, validation
feedback, horizontal overflow, migration preview and result counts, and serious
or critical axe violations. Agent editor capability and publish snapshots plus
desktop and narrow migration snapshots are checked with `toHaveScreenshot`; the
snapshot files and test results must be attached to release evidence before
enabling the agent projection flag.
