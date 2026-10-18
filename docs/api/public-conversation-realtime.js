export function createRealtimeDiagnostics({ now = () => Date.now(), maxEvents = 80 } = {}) {
  let connection = "idle";
  let conversationId = "";
  let agentVersion = "";
  let lastEventAt = null;
  let lastTurnLatencyMs = null;
  let lastError = "";
  const events = [];
  const turnStartedAt = new Map();

  function addEvent(event) {
    const details = event.details || {};
    const isTool = typeof event.type === "string" && event.type.startsWith("tool.");
    const item = {
      type: event.type,
      turnId: event.turn_id || "",
      at: now(),
      ...(isTool ? {
        name: typeof details.name === "string" ? details.name : "",
        durationMs: Number.isInteger(details.duration_ms) ? details.duration_ms : null,
        code: typeof details.code === "string" ? details.code : "",
      } : {}),
    };
    events.push(item);
    while (events.length > maxEvents) events.shift();
    lastEventAt = item.at;
    if (event.type === "turn.started" && item.turnId) turnStartedAt.set(item.turnId, item.at);
    if (event.type === "turn.completed" && item.turnId && turnStartedAt.has(item.turnId)) {
      lastTurnLatencyMs = item.at - turnStartedAt.get(item.turnId);
      turnStartedAt.delete(item.turnId);
    }
    if (event.type === "error") lastError = event.details?.message || event.details?.code || "请求失败";
  }

  return {
    socketOpen() {
      connection = "connecting";
    },
    sessionReady(session) {
      connection = "connected";
      conversationId = session.conversationId || "";
      agentVersion = String(session.agentVersion || "");
      addEvent({ type: "session.ready" });
    },
    event: addEvent,
    socketClosed(code, reason) {
      connection = "closed";
      if (code && !lastError) lastError = reason || `连接关闭（${code}）`;
    },
    clear() {
      connection = "idle";
      conversationId = "";
      agentVersion = "";
      lastEventAt = null;
      lastTurnLatencyMs = null;
      lastError = "";
      events.length = 0;
      turnStartedAt.clear();
    },
    clearEvents() {
      lastEventAt = null;
      lastTurnLatencyMs = null;
      lastError = "";
      events.length = 0;
      turnStartedAt.clear();
    },
    snapshot() {
      return {
        connection,
        conversationId,
        agentVersion,
        events: events.slice(),
        lastEventAt,
        lastTurnLatencyMs,
        lastError,
      };
    },
  };
}
