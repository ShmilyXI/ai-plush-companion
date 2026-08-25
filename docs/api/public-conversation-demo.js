import { createAudioObjectUrl, createAudioPlaybackQueue, createPlaybackDucker } from "./public-conversation-audio.js?v=20260825-2";
import { createAudioSendQueue } from "./public-conversation-send-queue.js?v=20260825-1";
import { createVoiceSegmenter } from "./public-conversation-vad.js?v=20260825-1";
import { createConversationTurnStore } from "./public-conversation-turn-model.js";
import { createRealtimeDiagnostics } from "./public-conversation-realtime.js";

const runtimeConfig = await fetch("./public-conversation-demo.runtime.json?v=20260825-1")
  .then((response) => response.ok ? response.json() : {})
  .catch(() => ({}));
const apiBase = document.querySelector("#api-base");
const authorization = document.querySelector("#authorization");
const agentId = document.querySelector("#agent-id");
const connectButton = document.querySelector("#connect-button");
const connectionNote = document.querySelector("#connection-note");
const settingsButton = document.querySelector("#settings-button");
const connectionPanel = document.querySelector("#connection-panel");
const roleName = document.querySelector("#role-name");
const statusDot = document.querySelector("#status-dot");
const messageList = document.querySelector("#message-list");
const emptyState = document.querySelector("#empty-state");
const textInput = document.querySelector("#text-input");
const sendButton = document.querySelector("#send-button");
const recordButton = document.querySelector("#record-button");
const realtimeTextInput = document.querySelector("#realtime-text-input");
const realtimeSendButton = document.querySelector("#realtime-send-button");
const realtimeRecordButton = document.querySelector("#realtime-record-button");
const realtimeMessageList = document.querySelector("#realtime-message-list");
const realtimeEmptyState = document.querySelector("#realtime-empty-state");
const realtimeModeButton = document.querySelector("#realtime-mode-button");
const chatTabButton = document.querySelector("#chat-tab-button");
const realtimeTabButton = document.querySelector("#realtime-tab-button");
const chatTab = document.querySelector("#chat-tab");
const realtimeTab = document.querySelector("#realtime-tab");
const clearDiagnosticsButton = document.querySelector("#clear-diagnostics");
const realtimeConnection = document.querySelector("#realtime-connection");
const realtimeSession = document.querySelector("#realtime-session");
const realtimeAgentVersion = document.querySelector("#realtime-agent-version");
const realtimeLatency = document.querySelector("#realtime-latency");
const realtimeMic = document.querySelector("#realtime-mic");
const realtimeQueued = document.querySelector("#realtime-queued");
const realtimeActiveTurns = document.querySelector("#realtime-active-turns");
const realtimeVolume = document.querySelector("#realtime-volume");
const realtimeError = document.querySelector("#realtime-error");
const eventLog = document.querySelector("#event-log");
const localConfig = { ...(window.__PUBLIC_DEMO_CONFIG__ || {}), ...runtimeConfig };

if (localConfig.apiBase) apiBase.value = localConfig.apiBase;
if (localConfig.authorization) authorization.value = localConfig.authorization;
if (localConfig.agentId) agentId.value = localConfig.agentId;

let session = null;
let conversation = null;
let activeAssistantTurn = null;
let audioContext = null;
let mediaStream = null;
let processor = null;
let pcmChunks = [];
let recordingStartedAt = 0;
let recordingState = "idle";
let recordingStopRequested = false;
let recordingPointerId = null;
let recordingButton = recordButton;
let realtimeModeActive = false;
let realtimeAudioContext = null;
let realtimeMediaStream = null;
let realtimeProcessor = null;
let realtimeSegmenter = null;
let realtimeSendQueue = null;
let realtimeSpeechActive = false;
const realtimeSegmentWaiters = new Map();
const realtimeTurnRequests = new Map();
let audioPlaybackActive = false;
let audioPlaybackBlocked = false;
let audioPlaybackFailed = false;
const turnStore = createConversationTurnStore();
const realtimeDiagnostics = createRealtimeDiagnostics();
const renderedTurns = new Map();
const realtimeRenderedTurns = new Map();
const persistentAudioUrls = new Set();
const playbackDucker = createPlaybackDucker({ duckVolume: 0.2 });

function setStatus(kind, message) {
  statusDot.className = `status-dot${kind ? ` ${kind}` : ""}`;
  statusDot.title = message;
  statusDot.setAttribute("aria-label", message);
  connectionNote.textContent = message;
}

function renderRealtimeDiagnostics() {
  const snapshot = realtimeDiagnostics.snapshot();
  const connectionLabels = { idle: "未连接", connecting: "连接中", connected: "已连接", closed: "已关闭" };
  realtimeConnection.textContent = connectionLabels[snapshot.connection] || snapshot.connection;
  realtimeConnection.dataset.state = snapshot.connection;
  realtimeSession.textContent = snapshot.conversationId ? snapshot.conversationId.slice(0, 8) : "--";
  realtimeSession.title = snapshot.conversationId || "";
  realtimeAgentVersion.textContent = snapshot.agentVersion || "--";
  realtimeLatency.textContent = snapshot.lastTurnLatencyMs == null ? "--" : `${snapshot.lastTurnLatencyMs} ms`;
  realtimeMic.textContent = realtimeAudioContext ? (realtimeSpeechActive ? "说话中" : "持续收音") : "未开启";
  realtimeQueued.textContent = String(realtimeSendQueue?.snapshot().queued || 0);
  realtimeActiveTurns.textContent = String(realtimeSendQueue?.snapshot().active || 0);
  realtimeVolume.textContent = realtimeSpeechActive ? "20%" : "100%";
  realtimeError.textContent = snapshot.lastError;
  realtimeError.hidden = !snapshot.lastError;
  eventLog.replaceChildren();
  if (!snapshot.events.length) {
    const empty = document.createElement("span");
    empty.className = "event-log-empty";
    empty.textContent = "连接后显示实时事件";
    eventLog.append(empty);
    return;
  }
  for (const item of snapshot.events) {
    const row = document.createElement("div");
    row.className = "event-row";
    const type = document.createElement("strong");
    type.textContent = item.type;
    const turn = document.createElement("span");
    if (item.type.startsWith("tool.")) {
      const suffix = item.durationMs != null ? ` · ${item.durationMs} ms` : (item.code ? ` · ${item.code}` : "");
      turn.textContent = `${item.name || "工具"}${suffix}`;
    } else {
      turn.textContent = item.turnId ? item.turnId.slice(0, 8) : "连接";
    }
    row.append(type, turn);
    eventLog.append(row);
  }
  eventLog.scrollTop = eventLog.scrollHeight;
}

function connectedStatus() {
  if (audioPlaybackActive) return "正在播放语音";
  if (audioPlaybackBlocked) return "点击页面开启声音";
  if (audioPlaybackFailed) return "语音播放失败，文字回复可用";
  return "已连接";
}

function setRealtimeControlsEnabled(enabled) {
  const socketReady = conversation?.readyState === WebSocket.OPEN;
  [realtimeTextInput, realtimeSendButton, realtimeRecordButton].forEach((control) => {
    control.disabled = !(enabled && socketReady);
  });
}

async function sendRealtimeSegment(segment) {
  if (!conversation || conversation.readyState !== WebSocket.OPEN) throw new Error("实时连接已关闭");
  const requestId = crypto.randomUUID();
  const turn = ensureTurn(requestId, "audio", { audioUrl: createWavUrl(segment.pcm, 16000) });
  updateRealtimeTurnView(turn);
  const result = new Promise((resolve, reject) => realtimeSegmentWaiters.set(requestId, { resolve, reject }));
  conversation.send(JSON.stringify({
    type: "turn.audio.start",
    request_id: requestId,
    duration_ms: segment.durationMs,
  }));
  conversation.send(segment.pcm.buffer);
  conversation.send(JSON.stringify({ type: "turn.audio.end" }));
  return result;
}

async function startRealtimeCapture() {
  if (realtimeAudioContext || !conversation || conversation.readyState !== WebSocket.OPEN) return;
  try {
    realtimeMediaStream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } });
    realtimeAudioContext = new AudioContext();
    const source = realtimeAudioContext.createMediaStreamSource(realtimeMediaStream);
    const silentSink = realtimeAudioContext.createGain();
    realtimeProcessor = realtimeAudioContext.createScriptProcessor(4096, 1, 1);
    silentSink.gain.value = 0;
    realtimeSegmenter = createVoiceSegmenter({
      onSpeechState(value) {
        realtimeSpeechActive = value;
        playbackDucker.setUserSpeaking(value);
        renderRealtimeDiagnostics();
      },
      onSegment(segment) {
        void realtimeSendQueue.enqueue(segment).catch((error) => {
          realtimeDiagnostics.event({ type: "error", details: { code: error.code || "send_failed" } });
          renderRealtimeDiagnostics();
        });
      },
    });
    realtimeSendQueue = createAudioSendQueue({
      maxActive: 2,
      send: sendRealtimeSegment,
      onError: (error) => {
        realtimeDiagnostics.event({ type: "error", details: { code: error.code || "send_failed" } });
        renderRealtimeDiagnostics();
      },
    });
    realtimeProcessor.addEventListener("audioprocess", (event) => {
      if (!realtimeSegmenter || !realtimeAudioContext) return;
      const input = event.inputBuffer.getChannelData(0);
      realtimeSegmenter.push(downsample(input, realtimeAudioContext.sampleRate, 16000));
    });
    source.connect(realtimeProcessor);
    realtimeProcessor.connect(silentSink);
    silentSink.connect(realtimeAudioContext.destination);
    setRealtimeControlsEnabled(true);
    renderRealtimeDiagnostics();
  } catch (error) {
    await stopRealtimeCapture();
    setStatus("online", error.name === "NotAllowedError" ? "麦克风权限被拒绝" : "实时收音启动失败");
  }
}

async function stopRealtimeCapture() {
  realtimeSegmenter?.flush();
  realtimeSegmenter?.reset();
  realtimeSendQueue?.clear();
  realtimeProcessor?.disconnect();
  realtimeMediaStream?.getTracks().forEach((track) => track.stop());
  if (realtimeAudioContext) await realtimeAudioContext.close().catch(() => {});
  realtimeSegmenter = null;
  realtimeSendQueue = null;
  realtimeProcessor = null;
  realtimeMediaStream = null;
  realtimeAudioContext = null;
  realtimeSpeechActive = false;
  playbackDucker.setUserSpeaking(false);
  renderRealtimeDiagnostics();
}

async function setRealtimeMode(active) {
  if (active && conversation?.readyState !== WebSocket.OPEN) {
    setStatus("", "请先连接角色");
    return;
  }
  realtimeModeActive = active;
  realtimeEmptyState.hidden = active;
  realtimeModeButton.textContent = active ? "■ 退出实时通话" : "▶ 开始实时通话";
  realtimeModeButton.classList.toggle("recording", active);
  realtimeModeButton.setAttribute("aria-pressed", String(active));
  setRealtimeControlsEnabled(active && Boolean(realtimeAudioContext));
  if (active) {
    await startRealtimeCapture();
    if (!realtimeAudioContext) {
      realtimeModeActive = false;
      realtimeEmptyState.hidden = false;
      realtimeModeButton.textContent = "▶ 开始实时通话";
      realtimeModeButton.classList.remove("recording");
      realtimeModeButton.setAttribute("aria-pressed", "false");
      setRealtimeControlsEnabled(false);
    }
  } else await stopRealtimeCapture();
  if (active) setStatus("online", "实时通话已开启");
  else if (conversation?.readyState === WebSocket.OPEN) setStatus("online", connectedStatus());
}

function rememberAudioUrl(url) {
  persistentAudioUrls.add(url);
  return url;
}

function releasePersistentAudio() {
  for (const url of persistentAudioUrls) URL.revokeObjectURL(url);
  persistentAudioUrls.clear();
}

function stopVisibleAudio() {
  messageList.querySelectorAll("audio").forEach((audio) => audio.pause());
}

function createAudioElement(url, label) {
  const audio = document.createElement("audio");
  audio.controls = true;
  audio.preload = "metadata";
  audio.src = url;
  audio.setAttribute("aria-label", label);
  playbackDucker.register(audio);
  audio.addEventListener("ended", () => playbackDucker.unregister(audio), { once: true });
  return audio;
}

function renderRealtimeTurn(turn) {
  if (realtimeRenderedTurns.has(turn)) return realtimeRenderedTurns.get(turn);
  realtimeEmptyState.hidden = true;
  const wrapper = document.createElement("article");
  wrapper.className = "conversation-turn";
  wrapper.dataset.turnId = turn.turnId || turn.user.requestId;
  const userMessage = document.createElement("div");
  userMessage.className = "message user";
  const userBubble = document.createElement("div");
  userBubble.className = "bubble voice-bubble";
  const userContent = document.createElement("div");
  userContent.className = "message-content";
  userBubble.append(userContent);
  userMessage.append(userBubble);
  const assistantMessage = document.createElement("div");
  assistantMessage.className = "message assistant";
  const assistantBubble = document.createElement("div");
  assistantBubble.className = "bubble voice-bubble";
  const assistantContent = document.createElement("div");
  assistantContent.className = "message-content";
  assistantBubble.append(assistantContent);
  assistantMessage.append(assistantBubble);
  wrapper.append(userMessage, assistantMessage);
  realtimeMessageList.append(wrapper);
  const view = { wrapper, userMessage, userContent, assistantMessage, assistantContent, userAudio: null, assistantAudio: null };
  realtimeRenderedTurns.set(turn, view);
  updateRealtimeTurnView(turn);
  return view;
}

function updateRealtimeTurnView(turn) {
  const view = realtimeRenderedTurns.get(turn) || renderRealtimeTurn(turn);
  view.wrapper.dataset.turnId = turn.turnId || turn.user.requestId;
  view.userContent.replaceChildren();
  if (turn.user.audioUrl) {
    if (!view.userAudio || view.userAudio.src !== turn.user.audioUrl) view.userAudio = createAudioElement(turn.user.audioUrl, "播放我的录音");
    view.userContent.append(view.userAudio);
  }
  if (turn.user.text || turn.user.asrText) {
    const userText = document.createElement("div");
    userText.className = "realtime-user-text";
    userText.textContent = turn.user.asrText || turn.user.text;
    view.userContent.append(userText);
  }
  view.assistantMessage.hidden = !turn.assistant.audioUrl && !turn.assistant.text;
  view.assistantContent.replaceChildren();
  if (turn.assistant.audioUrl) {
    if (!view.assistantAudio || view.assistantAudio.src !== turn.assistant.audioUrl) view.assistantAudio = createAudioElement(turn.assistant.audioUrl, "播放 AI 回复");
    view.assistantContent.append(view.assistantAudio);
  }
  if (turn.assistant.text) {
    const assistantText = document.createElement("div");
    assistantText.className = "assistant-text";
    assistantText.textContent = turn.assistant.text;
    view.assistantContent.append(assistantText);
  }
  realtimeMessageList.scrollTop = realtimeMessageList.scrollHeight;
}

function renderTurn(turn) {
  if (renderedTurns.has(turn)) return renderedTurns.get(turn);
  emptyState.hidden = true;
  const wrapper = document.createElement("article");
  wrapper.className = "conversation-turn";
  wrapper.dataset.turnId = turn.turnId || turn.user.requestId;

  const userMessage = document.createElement("div");
  userMessage.className = "message user";
  const userBubble = document.createElement("div");
  userBubble.className = "bubble voice-bubble";
  const userContent = document.createElement("div");
  userContent.className = "message-content";
  userBubble.append(userContent);
  userMessage.append(userBubble);
  wrapper.append(userMessage);

  const assistantMessage = document.createElement("div");
  assistantMessage.className = "message assistant";
  const assistantBubble = document.createElement("div");
  assistantBubble.className = "bubble voice-bubble";
  const assistantContent = document.createElement("div");
  assistantContent.className = "message-content";
  assistantBubble.append(assistantContent);
  assistantMessage.append(assistantBubble);
  wrapper.append(assistantMessage);
  messageList.append(wrapper);

  const view = {
    wrapper,
    userMessage,
    userContent,
    assistantMessage,
    assistantContent,
    transcriptButton: null,
    transcriptText: null,
    userAudio: null,
    assistantAudio: null,
  };
  renderedTurns.set(turn, view);
  updateTurnView(turn);
  messageList.scrollTop = messageList.scrollHeight;
  return view;
}

function updateTurnView(turn) {
  const view = renderedTurns.get(turn) || renderTurn(turn);
  view.wrapper.dataset.turnId = turn.turnId || turn.user.requestId;
  view.userMessage.className = `message user${turn.user.inputMode === "audio" ? " audio-message" : ""}`;
  view.assistantMessage.hidden = !turn.assistant.audioUrl && !turn.assistant.text;
  view.userContent.replaceChildren();

  if (turn.user.inputMode === "audio") {
    if (turn.user.audioUrl && !view.userAudio) view.userAudio = createAudioElement(turn.user.audioUrl, "播放我的录音");
    if (view.userAudio) view.userContent.append(view.userAudio);
    if (!view.transcriptButton) {
      view.transcriptButton = document.createElement("button");
      view.transcriptButton.type = "button";
      view.transcriptButton.className = "transcript-button";
      view.transcriptButton.dataset.action = "transcript";
      view.transcriptButton.textContent = "转文字";
      view.transcriptText = document.createElement("div");
      view.transcriptText.className = "transcript-text";
    }
    view.userContent.append(view.transcriptButton, view.transcriptText);
    view.transcriptText.textContent = turn.user.asrText || "识别中...";
    view.transcriptText.hidden = !turn.user.transcriptVisible;
    view.transcriptButton.textContent = turn.user.transcriptVisible ? "收起文字" : "转文字";
  } else {
    view.userContent.textContent = turn.user.text;
  }

  view.assistantContent.replaceChildren();
  if (turn.assistant.audioUrl) {
    if (!view.assistantAudio || view.assistantAudio.src !== turn.assistant.audioUrl) view.assistantAudio = createAudioElement(turn.assistant.audioUrl, "播放 AI 回复");
    view.assistantContent.append(view.assistantAudio);
  }
  if (turn.assistant.text) {
    const text = document.createElement("div");
    text.className = "assistant-text";
    text.textContent = turn.assistant.text;
    view.assistantContent.append(text);
  }
  messageList.scrollTop = messageList.scrollHeight;
  updateRealtimeTurnView(turn);
}

function ensureTurn(requestId, inputMode, data = {}) {
  const turn = turnStore.ensure({ requestId, inputMode, ...data });
  renderTurn(turn);
  return turn;
}

function fallbackTurn(turnId) {
  const requestId = `server-${turnId}`;
  const turn = ensureTurn(requestId, "text");
  turnStore.bindTurnId(requestId, turnId);
  updateTurnView(turn);
  return turn;
}

const audioPlayback = createAudioPlaybackQueue({
  onBlocked() {
    audioPlaybackActive = false;
    audioPlaybackBlocked = true;
    setStatus("online", connectedStatus());
  },
  onError() {
    audioPlaybackActive = false;
    audioPlaybackFailed = true;
    setStatus("online", connectedStatus());
  },
  onIdle() {
    audioPlaybackActive = false;
    setStatus("online", connectedStatus());
  },
  onPlaying() {
    audioPlaybackActive = true;
    audioPlaybackBlocked = false;
    audioPlaybackFailed = false;
    setStatus("busy", "正在播放语音");
  },
  onAudioCreated(audio) {
    playbackDucker.register(audio);
    if (realtimeSpeechActive) playbackDucker.setUserSpeaking(true);
  },
  onAudioReleased(audio) {
    playbackDucker.unregister(audio);
  },
});

settingsButton.addEventListener("click", () => {
  connectionPanel.hidden = !connectionPanel.hidden;
});

function handleEvent(event) {
  const details = event.details || {};
  const turnId = event.turn_id;
  if (event.type === "session.ready") {
    realtimeDiagnostics.sessionReady(session);
    renderRealtimeDiagnostics();
    setStatus("online", `已连接 · 版本 ${details.agent_version || session.agentVersion}`);
  } else if (event.type === "turn.started") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const requestId = details.request_id || `server-${turnId}`;
    realtimeTurnRequests.set(turnId, requestId);
    const turn = ensureTurn(requestId, details.input_mode || "text");
    turnStore.bindTurnId(requestId, turnId);
    activeAssistantTurn = turn;
    updateTurnView(turn);
    setStatus("busy", "正在处理");
  } else if (event.type === "llm.delta") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const turn = turnStore.get(turnId) || activeAssistantTurn || fallbackTurn(turnId);
    turnStore.appendAssistantText(turn.turnId, details.text || "");
    updateTurnView(turn);
  } else if (event.type === "asr.final") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const turn = turnStore.get(turnId) || fallbackTurn(turnId);
    turnStore.setAsr(turn.turnId, details.text || "");
    updateTurnView(turn);
  } else if (event.type === "tts.audio") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const turn = turnStore.get(turnId) || activeAssistantTurn || fallbackTurn(turnId);
    try {
      turnStore.setAssistantAudio(turn.turnId, rememberAudioUrl(createAudioObjectUrl(details)));
      updateTurnView(turn);
    } catch (error) {
      audioPlaybackFailed = true;
      setStatus("online", error.message || "语音数据无效");
    }
    void audioPlayback.enqueue(details);
  } else if (event.type === "turn.completed") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const turn = turnStore.get(turnId) || activeAssistantTurn || fallbackTurn(turnId);
    turnStore.setAssistantText(turn.turnId, details.text || turn.assistant.text);
    updateTurnView(turn);
    if (activeAssistantTurn === turn) activeAssistantTurn = null;
    const requestId = realtimeTurnRequests.get(turnId);
    if (requestId) {
      realtimeTurnRequests.delete(turnId);
      realtimeSegmentWaiters.get(requestId)?.resolve();
      realtimeSegmentWaiters.delete(requestId);
    }
    setStatus(audioPlaybackActive ? "busy" : "online", connectedStatus());
  } else if (event.type === "turn.cancelled") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    activeAssistantTurn = null;
    setStatus("online", "已取消");
  } else if (event.type === "error") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    const turn = turnId ? (turnStore.get(turnId) || activeAssistantTurn) : null;
    if (turn) {
      turnStore.setAssistantText(turn.turnId, details.message || details.code || "请求失败");
      updateTurnView(turn);
    }
    activeAssistantTurn = null;
    const requestId = turnId ? realtimeTurnRequests.get(turnId) : null;
    if (requestId) {
      realtimeTurnRequests.delete(turnId);
      realtimeSegmentWaiters.get(requestId)?.reject(new Error(details.message || details.code || "turn failed"));
      realtimeSegmentWaiters.delete(requestId);
    }
    setStatus("online", "连接仍在，上一轮失败");
  } else if (event.type === "session.expired") {
    realtimeDiagnostics.event(event);
    renderRealtimeDiagnostics();
    audioPlayback.clear();
    setStatus("", "会话已过期");
    [textInput, sendButton, recordButton].forEach((control) => { control.disabled = true; });
    setRealtimeMode(false);
  }
}

async function connect() {
  connectButton.disabled = true;
  setStatus("busy", "正在连接");
  conversation?.close();
  conversation = null;
  setRealtimeMode(false);
  realtimeDiagnostics.clear();
  renderRealtimeDiagnostics();
  stopVisibleAudio();
  audioPlayback.clear();
  audioPlaybackActive = false;
  audioPlaybackBlocked = false;
  audioPlaybackFailed = false;
  try {
    const callerAuthorization = authorization.value.trim() || localConfig.authorization || localConfig.authToken || "";
    const response = await fetch(`${apiBase.value.replace(/\/$/, "")}/api/v1/conversations`, {
      method: "POST",
      headers: { Authorization: callerAuthorization, "Content-Type": "application/json" },
      body: JSON.stringify({ agentId: agentId.value.trim(), inputModes: ["text", "audio"], outputModes: ["text", "audio"] }),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.code && payload.code !== 0) throw new Error(payload.msg || payload.message || `连接失败（${response.status}）`);
    session = payload.data || payload;
    if (!session.runtimeToken || !session.streamUrl) throw new Error("服务端没有返回有效会话");
    roleName.textContent = session.publicMetadata?.agent_name || session.agentId;
    realtimeDiagnostics.socketOpen();
    renderRealtimeDiagnostics();
    const socket = new WebSocket(session.streamUrl, [`bearer.${session.runtimeToken}`]);
    conversation = socket;
    socket.addEventListener("message", (event) => {
      if (conversation !== socket || typeof event.data !== "string") return;
      handleEvent(JSON.parse(event.data));
    });
    socket.addEventListener("open", () => {
      if (conversation !== socket) return;
      [textInput, sendButton, recordButton].forEach((control) => { control.disabled = false; });
      setRealtimeControlsEnabled(realtimeModeActive);
    });
    socket.addEventListener("close", (event) => {
      if (conversation !== socket) return;
      audioPlayback.clear();
      realtimeDiagnostics.socketClosed(event.code, event.reason);
      renderRealtimeDiagnostics();
      [textInput, sendButton, recordButton].forEach((control) => { control.disabled = true; });
      setRealtimeMode(false);
      setStatus("", "连接已关闭");
    });
    socket.addEventListener("error", () => {
      if (conversation === socket) setStatus("", "连接失败");
    });
  } catch (error) {
    realtimeDiagnostics.socketClosed(0, error.message || "连接失败");
    renderRealtimeDiagnostics();
    setStatus("", error.message || "连接失败");
  } finally {
    connectButton.disabled = false;
  }
}

function sendText(input = textInput) {
  const text = input.value.trim();
  if (!text || !conversation || conversation.readyState !== WebSocket.OPEN) return;
  const requestId = crypto.randomUUID();
  ensureTurn(requestId, "text", { text });
  conversation.send(JSON.stringify({ type: "turn.text", request_id: requestId, text }));
  input.value = "";
}

function downsample(buffer, inputRate, outputRate) {
  if (inputRate === outputRate) return buffer;
  const ratio = inputRate / outputRate;
  const result = new Float32Array(Math.round(buffer.length / ratio));
  let resultOffset = 0;
  let bufferOffset = 0;
  while (resultOffset < result.length) {
    const nextOffset = Math.round((resultOffset + 1) * ratio);
    let total = 0;
    let count = 0;
    for (let index = bufferOffset; index < nextOffset && index < buffer.length; index += 1) {
      total += buffer[index];
      count += 1;
    }
    result[resultOffset] = count ? total / count : 0;
    resultOffset += 1;
    bufferOffset = nextOffset;
  }
  return result;
}

function encodePcm(chunks, sampleRate) {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const pcm = new Int16Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    for (let index = 0; index < chunk.length; index += 1) pcm[offset + index] = Math.max(-1, Math.min(1, chunk[index])) * 0x7fff;
    offset += chunk.length;
  }
  return { pcm: new Uint8Array(pcm.buffer), durationMs: Math.round((total / sampleRate) * 1000), sampleRate };
}

function createWavUrl(bytes, sampleRate) {
  const buffer = new ArrayBuffer(44 + bytes.byteLength);
  const view = new DataView(buffer);
  const write = (offset, value) => [...value].forEach((character, index) => view.setUint8(offset + index, character.charCodeAt(0)));
  write(0, "RIFF");
  view.setUint32(4, 36 + bytes.byteLength, true);
  write(8, "WAVE");
  write(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  write(36, "data");
  view.setUint32(40, bytes.byteLength, true);
  new Uint8Array(buffer, 44).set(bytes);
  return rememberAudioUrl(URL.createObjectURL(new Blob([buffer], { type: "audio/wav" })));
}

async function startRecording(targetButton = recordButton) {
  if (recordingState !== "idle" || !conversation || conversation.readyState !== WebSocket.OPEN || !navigator.mediaDevices) return;
  recordingState = "starting";
  recordingButton = targetButton;
  recordingStopRequested = false;
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true } });
    audioContext = new AudioContext();
    const source = audioContext.createMediaStreamSource(mediaStream);
    const silentSink = audioContext.createGain();
    processor = audioContext.createScriptProcessor(4096, 1, 1);
    silentSink.gain.value = 0;
    pcmChunks = [];
    recordingStartedAt = performance.now();
    processor.addEventListener("audioprocess", (event) => pcmChunks.push(downsample(event.inputBuffer.getChannelData(0), audioContext.sampleRate, 16000)));
    source.connect(processor);
    processor.connect(silentSink);
    silentSink.connect(audioContext.destination);
    recordingState = "recording";
    recordingButton.classList.add("recording");
    recordingButton.textContent = "松开即发送";
    setStatus("busy", "正在录音");
    if (recordingStopRequested) await stopRecording();
  } catch (error) {
    mediaStream?.getTracks().forEach((track) => track.stop());
    mediaStream = null;
    audioContext = null;
    processor = null;
    recordingState = "idle";
    setStatus("online", error.name === "NotAllowedError" ? "麦克风权限被拒绝" : "无法开始录音");
  }
}

async function stopRecording() {
  if (recordingState === "starting") {
    recordingStopRequested = true;
    return;
  }
  if (recordingState !== "recording" || !audioContext || !conversation || conversation.readyState !== WebSocket.OPEN) return;
  recordingState = "stopping";
  processor?.disconnect();
  mediaStream?.getTracks().forEach((track) => track.stop());
  const { pcm, durationMs, sampleRate } = encodePcm(pcmChunks, 16000);
  const requestId = crypto.randomUUID();
  const turn = ensureTurn(requestId, "audio", { audioUrl: createWavUrl(pcm, sampleRate) });
  conversation.send(JSON.stringify({ type: "turn.audio.start", request_id: requestId, duration_ms: Math.max(durationMs, Math.round(performance.now() - recordingStartedAt)) }));
  conversation.send(pcm.buffer);
  conversation.send(JSON.stringify({ type: "turn.audio.end" }));
  updateTurnView(turn);
  await audioContext.close();
  audioContext = null;
  processor = null;
  mediaStream = null;
  pcmChunks = [];
  recordingState = "idle";
  recordingStopRequested = false;
  recordingButton.classList.remove("recording");
  recordingButton.textContent = "按住录音";
  setStatus("busy", "正在处理");
}

function requestRecordingStop() {
  if (recordingState === "starting") recordingStopRequested = true;
  else if (recordingState === "recording") void stopRecording();
}

connectionPanel.addEventListener("submit", (event) => {
  event.preventDefault();
  connect();
});
clearDiagnosticsButton.addEventListener("click", () => {
  realtimeDiagnostics.clearEvents();
  renderRealtimeDiagnostics();
});
function bindComposer(input, send, button) {
  send.addEventListener("click", () => sendText(input));
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      sendText(input);
    }
  });
  button.addEventListener("pointerdown", (event) => {
    if (button === realtimeRecordButton && realtimeModeActive) return;
    event.preventDefault();
    recordingPointerId = event.pointerId;
    recordingButton = button;
    try { button.setPointerCapture?.(event.pointerId); } catch {}
    void startRecording(button);
  });
  button.addEventListener("pointerup", (event) => {
    if (recordingPointerId === event.pointerId) requestRecordingStop();
    recordingPointerId = null;
  });
  button.addEventListener("pointercancel", requestRecordingStop);
  button.addEventListener("pointerleave", (event) => {
    if (recordingPointerId === event.pointerId && !button.hasPointerCapture?.(event.pointerId)) requestRecordingStop();
  });
  button.addEventListener("contextmenu", (event) => event.preventDefault());
  button.addEventListener("keydown", (event) => {
    if (button === realtimeRecordButton && realtimeModeActive) return;
    if ((event.key === " " || event.key === "Enter") && !event.repeat) {
      event.preventDefault();
      recordingButton = button;
      void startRecording(button);
    }
  });
  button.addEventListener("keyup", (event) => {
    if (event.key === " " || event.key === "Enter") {
      event.preventDefault();
      requestRecordingStop();
    }
  });
}

bindComposer(textInput, sendButton, recordButton);
bindComposer(realtimeTextInput, realtimeSendButton, realtimeRecordButton);
realtimeModeButton.addEventListener("click", () => {
  if (!realtimeModeActive && conversation?.readyState !== WebSocket.OPEN) {
    setStatus("", "请先连接角色");
    return;
  }
  setRealtimeMode(!realtimeModeActive);
});

function selectTab(button, panel, otherButton, otherPanel) {
  button.classList.add("active");
  button.setAttribute("aria-selected", "true");
  otherButton.classList.remove("active");
  otherButton.setAttribute("aria-selected", "false");
  panel.hidden = false;
  otherPanel.hidden = true;
}

chatTabButton.addEventListener("click", () => selectTab(chatTabButton, chatTab, realtimeTabButton, realtimeTab));
realtimeTabButton.addEventListener("click", () => selectTab(realtimeTabButton, realtimeTab, chatTabButton, chatTab));
messageList.addEventListener("click", (event) => {
  const button = event.target.closest("[data-action='transcript']");
  if (!button) return;
  const wrapper = button.closest(".conversation-turn");
  const turn = wrapper ? turnStore.get(wrapper.dataset.turnId) : null;
  if (!turn) return;
  turn.user.transcriptVisible = !turn.user.transcriptVisible;
  updateTurnView(turn);
});
document.addEventListener("pointerdown", () => {
  void audioPlayback.resume();
}, { passive: true });
window.addEventListener("beforeunload", () => {
  audioPlayback.dispose();
  releasePersistentAudio();
});
