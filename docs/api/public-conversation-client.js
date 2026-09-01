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
  let pendingAudioEvent = null;

  socket.onmessage = (message) => {
    if (typeof message.data === "string") {
      const event = JSON.parse(message.data);
      if ((event.type === "tts.audio" || event.type === "tts.audio.chunk")
        && event.details?.transport === "binary" && event.details?.data == null) pendingAudioEvent = event;
      onEvent?.(event);
      return;
    }
    if (pendingAudioEvent) {
      const event = pendingAudioEvent;
      pendingAudioEvent = null;
      onEvent?.({ ...event, details: { ...event.details, data: message.data } });
    } else onEvent?.({ type: "tts.audio.binary", data: message.data });
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
    startWebStream(requestId = crypto.randomUUID(), eventId = crypto.randomUUID()) {
      send({
        type: "web.session.start",
        protocol_version: 1,
        request_id: requestId,
        event_id: eventId,
        audio: { format: "pcm_s16le", sample_rate: 16000, channels: 1 },
      });
    },
    pushAudio(bytes) {
      if (socket.readyState !== WebSocket.OPEN) throw new Error("conversation socket is not open");
      socket.send(bytes);
    },
    commitAudio(requestId, durationMs, eventId = crypto.randomUUID()) {
      send({ type: "input.audio.commit", request_id: requestId, duration_ms: durationMs, event_id: eventId });
    },
    cancelResponse(turnId, playedMs = 0, eventId = crypto.randomUUID()) {
      send({ type: "response.cancel", turn_id: turnId, played_ms: playedMs, event_id: eventId });
    },
    heartbeat(nonce = crypto.randomUUID()) {
      send({ type: "web.session.ping", nonce });
    },
    stopWebStream(requestId, eventId = crypto.randomUUID()) {
      send({ type: "web.session.stop", request_id: requestId, event_id: eventId });
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
