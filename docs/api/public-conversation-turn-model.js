export function createConversationTurnStore() {
  const turns = [];
  const byRequestId = new Map();
  const byTurnId = new Map();

  function ensure({ requestId, inputMode, audioUrl = null, text = "" }) {
    const existing = byRequestId.get(requestId);
    if (existing) return existing;
    const turn = {
      turnId: null,
      user: { requestId, inputMode, audioUrl, text, asrText: "", transcriptVisible: false },
      assistant: { text: "", audioUrl: null },
    };
    turns.push(turn);
    byRequestId.set(requestId, turn);
    return turn;
  }

  function get(turnId) {
    return byTurnId.get(turnId) || byRequestId.get(turnId) || null;
  }

  return {
    ensure,
    bindTurnId(requestId, turnId) {
      const turn = byRequestId.get(requestId);
      if (!turn) return null;
      turn.turnId = turnId;
      byTurnId.set(turnId, turn);
      return turn;
    },
    get,
    setAsr(turnId, text) {
      const turn = get(turnId);
      if (turn) turn.user.asrText = text;
      return turn;
    },
    appendAssistantText(turnId, text) {
      const turn = get(turnId);
      if (turn) turn.assistant.text += text;
      return turn;
    },
    setAssistantText(turnId, text) {
      const turn = get(turnId);
      if (turn) turn.assistant.text = text;
      return turn;
    },
    setAssistantAudio(turnId, audioUrl) {
      const turn = get(turnId);
      if (turn) turn.assistant.audioUrl = audioUrl;
      return turn;
    },
    list() {
      return turns;
    },
  };
}
