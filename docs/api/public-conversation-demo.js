import { createAudioObjectUrl, createAudioPlaybackQueue } from "./public-conversation-audio.js";
import { createConversationTurnStore } from "./public-conversation-turn-model.js";

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
const localConfig = window.__PUBLIC_DEMO_CONFIG__ || {};

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
let audioPlaybackActive = false;
let audioPlaybackBlocked = false;
let audioPlaybackFailed = false;
const turnStore = createConversationTurnStore();
const renderedTurns = new Map();
const persistentAudioUrls = new Set();

function setStatus(kind, message) {
  statusDot.className = `status-dot${kind ? ` ${kind}` : ""}`;
  statusDot.title = message;
  statusDot.setAttribute("aria-label", message);
  connectionNote.textContent = message;
}

function connectedStatus() {
  if (audioPlaybackActive) return "正在播放语音";
  if (audioPlaybackBlocked) return "点击页面开启声音";
  if (audioPlaybackFailed) return "语音播放失败，文字回复可用";
  return "已连接";
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
  return audio;
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
});

settingsButton.addEventListener("click", () => {
  connectionPanel.hidden = !connectionPanel.hidden;
});

function handleEvent(event) {
  const details = event.details || {};
  const turnId = event.turn_id;
  if (event.type === "session.ready") {
    setStatus("online", `已连接 · 版本 ${details.agent_version || session.agentVersion}`);
  } else if (event.type === "turn.started") {
    const requestId = details.request_id || `server-${turnId}`;
    const turn = ensureTurn(requestId, details.input_mode || "text");
    turnStore.bindTurnId(requestId, turnId);
    activeAssistantTurn = turn;
    updateTurnView(turn);
    setStatus("busy", "正在处理");
  } else if (event.type === "llm.delta") {
    const turn = turnStore.get(turnId) || activeAssistantTurn || fallbackTurn(turnId);
    turnStore.appendAssistantText(turn.turnId, details.text || "");
    updateTurnView(turn);
  } else if (event.type === "asr.final") {
    const turn = turnStore.get(turnId) || fallbackTurn(turnId);
    turnStore.setAsr(turn.turnId, details.text || "");
    updateTurnView(turn);
  } else if (event.type === "tts.audio") {
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
    const turn = turnStore.get(turnId) || activeAssistantTurn || fallbackTurn(turnId);
    turnStore.setAssistantText(turn.turnId, details.text || turn.assistant.text);
    updateTurnView(turn);
    if (activeAssistantTurn === turn) activeAssistantTurn = null;
    setStatus(audioPlaybackActive ? "busy" : "online", connectedStatus());
  } else if (event.type === "turn.cancelled") {
    activeAssistantTurn = null;
    setStatus("online", "已取消");
  } else if (event.type === "error") {
    const turn = turnId ? (turnStore.get(turnId) || activeAssistantTurn) : null;
    if (turn) {
      turnStore.setAssistantText(turn.turnId, details.message || details.code || "请求失败");
      updateTurnView(turn);
    }
    activeAssistantTurn = null;
    setStatus("online", "连接仍在，上一轮失败");
  } else if (event.type === "session.expired") {
    audioPlayback.clear();
    setStatus("", "会话已过期");
    textInput.disabled = true;
    sendButton.disabled = true;
    recordButton.disabled = true;
  }
}

async function connect() {
  connectButton.disabled = true;
  setStatus("busy", "正在连接");
  conversation?.close();
  conversation = null;
  stopVisibleAudio();
  audioPlayback.clear();
  audioPlaybackActive = false;
  audioPlaybackBlocked = false;
  audioPlaybackFailed = false;
  try {
    const response = await fetch(`${apiBase.value.replace(/\/$/, "")}/api/v1/conversations`, {
      method: "POST",
      headers: { Authorization: authorization.value.trim(), "Content-Type": "application/json" },
      body: JSON.stringify({ agentId: agentId.value.trim(), inputModes: ["text", "audio"], outputModes: ["text", "audio"] }),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.code && payload.code !== 0) throw new Error(payload.msg || payload.message || `连接失败（${response.status}）`);
    session = payload.data || payload;
    if (!session.runtimeToken || !session.streamUrl) throw new Error("服务端没有返回有效会话");
    roleName.textContent = session.publicMetadata?.agent_name || session.agentId;
    const socket = new WebSocket(session.streamUrl, [`bearer.${session.runtimeToken}`]);
    conversation = socket;
    socket.addEventListener("message", (event) => {
      if (conversation !== socket || typeof event.data !== "string") return;
      handleEvent(JSON.parse(event.data));
    });
    socket.addEventListener("open", () => {
      if (conversation !== socket) return;
      textInput.disabled = false;
      sendButton.disabled = false;
      recordButton.disabled = false;
    });
    socket.addEventListener("close", () => {
      if (conversation !== socket) return;
      audioPlayback.clear();
      textInput.disabled = true;
      sendButton.disabled = true;
      recordButton.disabled = true;
      setStatus("", "连接已关闭");
    });
    socket.addEventListener("error", () => {
      if (conversation === socket) setStatus("", "连接失败");
    });
  } catch (error) {
    setStatus("", error.message || "连接失败");
  } finally {
    connectButton.disabled = false;
  }
}

function sendText() {
  const text = textInput.value.trim();
  if (!text || !conversation || conversation.readyState !== WebSocket.OPEN) return;
  const requestId = crypto.randomUUID();
  ensureTurn(requestId, "text", { text });
  conversation.send(JSON.stringify({ type: "turn.text", request_id: requestId, text }));
  textInput.value = "";
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

async function startRecording() {
  if (recordingState !== "idle" || !conversation || conversation.readyState !== WebSocket.OPEN || !navigator.mediaDevices) return;
  recordingState = "starting";
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
    recordButton.classList.add("recording");
    recordButton.textContent = "松开即发送";
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
  recordButton.classList.remove("recording");
  recordButton.textContent = "按住录音";
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
sendButton.addEventListener("click", sendText);
textInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    sendText();
  }
});
recordButton.addEventListener("pointerdown", (event) => {
  event.preventDefault();
  recordingPointerId = event.pointerId;
  try {
    recordButton.setPointerCapture?.(event.pointerId);
  } catch {
    // Synthetic and browser-dispatched pointer events can lack capture state.
  }
  void startRecording();
});
recordButton.addEventListener("pointerup", (event) => {
  if (recordingPointerId === event.pointerId) requestRecordingStop();
  recordingPointerId = null;
});
recordButton.addEventListener("pointercancel", requestRecordingStop);
recordButton.addEventListener("pointerleave", (event) => {
  if (recordingPointerId === event.pointerId && !recordButton.hasPointerCapture?.(event.pointerId)) requestRecordingStop();
});
recordButton.addEventListener("contextmenu", (event) => event.preventDefault());
recordButton.addEventListener("keydown", (event) => {
  if ((event.key === " " || event.key === "Enter") && !event.repeat) {
    event.preventDefault();
    void startRecording();
  }
});
recordButton.addEventListener("keyup", (event) => {
  if (event.key === " " || event.key === "Enter") {
    event.preventDefault();
    requestRecordingStop();
  }
});
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
