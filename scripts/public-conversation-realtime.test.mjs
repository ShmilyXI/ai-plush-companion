import assert from "node:assert/strict";
import test from "node:test";

import { createRealtimeDiagnostics } from "../docs/api/public-conversation-realtime.js";

test("tracks WebSocket session and ordered runtime events", () => {
  let now = 1000;
  const diagnostics = createRealtimeDiagnostics({ now: () => now });
  diagnostics.sessionReady({ conversationId: "c1", agentVersion: 20 });
  now += 120;
  diagnostics.event({ type: "turn.started", turn_id: "t1" });
  now += 340;
  diagnostics.event({ type: "llm.delta", turn_id: "t1" });
  now += 80;
  diagnostics.event({ type: "tts.audio", turn_id: "t1" });
  now += 160;
  diagnostics.event({ type: "turn.completed", turn_id: "t1" });

  const snapshot = diagnostics.snapshot();
  assert.equal(snapshot.connection, "connected");
  assert.equal(snapshot.conversationId, "c1");
  assert.equal(snapshot.agentVersion, "20");
  assert.deepEqual(snapshot.events.map((item) => item.type), [
    "session.ready", "turn.started", "llm.delta", "tts.audio", "turn.completed",
  ]);
  assert.equal(snapshot.lastTurnLatencyMs, 580);
});

test("marks connection failures and clears diagnostic history", () => {
  const diagnostics = createRealtimeDiagnostics({ now: () => 2000 });
  diagnostics.socketOpen();
  diagnostics.event({ type: "error", details: { code: "unauthorized" } });
  diagnostics.socketClosed(1008, "unauthorized");
  assert.equal(diagnostics.snapshot().connection, "closed");
  assert.equal(diagnostics.snapshot().lastError, "unauthorized");

  diagnostics.clear();
  assert.deepEqual(diagnostics.snapshot(), {
    connection: "idle",
    conversationId: "",
    agentVersion: "",
    events: [],
    lastEventAt: null,
    lastTurnLatencyMs: null,
    lastError: "",
  });
});

test("clears events without pretending the live socket disconnected", () => {
  const diagnostics = createRealtimeDiagnostics({ now: () => 3000 });
  diagnostics.sessionReady({ conversationId: "c2", agentVersion: 21 });
  diagnostics.event({ type: "turn.started", turn_id: "t2" });
  diagnostics.clearEvents();

  const snapshot = diagnostics.snapshot();
  assert.equal(snapshot.connection, "connected");
  assert.equal(snapshot.conversationId, "c2");
  assert.deepEqual(snapshot.events, []);
});
