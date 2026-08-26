/**
 * Small browser adapter for the device-independent public conversation API.
 * Keep the user token in the app's auth boundary and only pass the short-lived
 * runtime token to the WebSocket connection.
 */

export async function createConversation({
  apiBase,
  authorization,
  agentId,
  inputModes = ["text"],
  outputModes = ["text"],
  voiceId,
  modelOverrides,
}) {
  const body = { agentId, inputModes, outputModes };
  if (voiceId) body.voiceId = voiceId;
  if (modelOverrides) body.modelOverrides = modelOverrides;

  const response = await fetch(`${apiBase.replace(/\/$/, "")}/api/v1/conversations`, {
    method: "POST",
    headers: {
      Authorization: authorization,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(payload?.msg || payload?.message || `conversation creation failed (${response.status})`);
  }
  return payload?.data || payload;
}

export function connectConversation({ streamUrl, runtimeToken, binaryAudio = false, onEvent, onClose, onError }) {
  const socket = new WebSocket(streamUrl, [`bearer.${runtimeToken}`]);
  if (binaryAudio) socket.binaryType = "arraybuffer";

  socket.onmessage = (message) => {
    if (typeof message.data === "string") {
      onEvent?.(JSON.parse(message.data));
      return;
    }
    onEvent?.({ type: "tts.audio.binary", data: message.data });
  };
  socket.onclose = (event) => onClose?.(event);
  socket.onerror = (event) => onError?.(event);

  const send = (payload) => {
    if (socket.readyState !== WebSocket.OPEN) throw new Error("conversation socket is not open");
    socket.send(JSON.stringify(payload));
  };

  return {
    socket,
    sendText(requestId, text) {
      send({ type: "turn.text", request_id: requestId, text });
    },
    sendAudioStart(requestId, durationMs) {
      const payload = { type: "turn.audio.start", request_id: requestId };
      if (durationMs != null) payload.duration_ms = durationMs;
      send(payload);
    },
    sendAudioChunk(bytes) {
      if (socket.readyState !== WebSocket.OPEN) throw new Error("conversation socket is not open");
      socket.send(bytes);
    },
    sendAudioEnd() {
      send({ type: "turn.audio.end" });
    },
    cancel(turnId) {
      send({ type: "turn.cancel", turn_id: turnId });
    },
    history(limit = 20) {
      send({ type: "conversation.history", limit });
    },
    close() {
      socket.close();
    },
  };
}

export function connectFromSession(session, options = {}) {
  return connectConversation({
    ...options,
    streamUrl: session.streamUrl,
    runtimeToken: session.runtimeToken,
  });
}
