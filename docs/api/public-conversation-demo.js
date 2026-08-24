import { createAudioPlaybackQueue } from "./public-conversation-audio.js";

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
let activeAssistantBubble = null;
let audioContext = null;
let mediaStream = null;
let processor = null;
let pcmChunks = [];
let recordingStartedAt = 0;
let audioPlaybackBlocked = false;
let audioPlaybackFailed = false;

function connectedStatus() {
  if (audioPlaybackBlocked) return "点击页面开启声音";
  if (audioPlaybackFailed) return "语音播放失败，文字回复可用";
  return "已连接";
}

const audioPlayback = createAudioPlaybackQueue({
  onBlocked() {
    audioPlaybackBlocked = true;
    setStatus("online", connectedStatus());
  },
  onError() {
    audioPlaybackFailed = true;
    setStatus("online", connectedStatus());
  },
  onIdle() {
    setStatus("online", connectedStatus());
  },
  onPlaying() {
    audioPlaybackBlocked = false;
    audioPlaybackFailed = false;
    setStatus("busy", "正在播放语音");
  },
});

settingsButton.addEventListener("click", () => {
  connectionPanel.hidden = !connectionPanel.hidden;
});

function setStatus(kind, message) {
  statusDot.className = `status-dot${kind ? ` ${kind}` : ""}`;
  statusDot.title = message;
  statusDot.setAttribute("aria-label", message);
  connectionNote.textContent = message;
}

function addMessage(kind, text = "") {
  emptyState.hidden = true;
  const item = document.createElement("div");
  item.className = `message ${kind}`;
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  item.append(bubble);
  messageList.append(item);
  messageList.scrollTop = messageList.scrollHeight;
  return bubble;
}

function handleEvent(event) {
  if (event.type === "session.ready") {
    setStatus("online", `已连接 · 版本 ${event.details?.agent_version || session.agentVersion}`);
  } else if (event.type === "turn.started") {
    setStatus("busy", "正在处理");
    activeAssistantBubble = addMessage("assistant");
  } else if (event.type === "llm.delta") {
    if (!activeAssistantBubble) activeAssistantBubble = addMessage("assistant");
    activeAssistantBubble.textContent += event.details?.text || "";
  } else if (event.type === "asr.final") {
    addMessage("user", event.details?.text || "");
  } else if (event.type === "tts.audio") {
    void audioPlayback.enqueue(event.details);
  } else if (event.type === "turn.completed") {
    if (activeAssistantBubble && event.details?.text) activeAssistantBubble.textContent = event.details.text;
    activeAssistantBubble = null;
    setStatus("online", connectedStatus());
  } else if (event.type === "turn.cancelled") {
    activeAssistantBubble = null;
    setStatus("online", "已取消");
  } else if (event.type === "error") {
    addMessage("error", event.details?.message || event.details?.code || "请求失败");
    activeAssistantBubble = null;
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
  audioPlayback.clear();
  audioPlaybackBlocked = false;
  audioPlaybackFailed = false;
  try {
    const response = await fetch(`${apiBase.value.replace(/\/$/, "")}/api/v1/conversations`, {
      method: "POST",
      headers: { Authorization: authorization.value.trim(), "Content-Type": "application/json" },
      body: JSON.stringify({ agentId: agentId.value.trim(), inputModes: ["text", "audio"], outputModes: ["text", "audio"] }),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.code && payload.code !== 0) {
      throw new Error(payload.msg || payload.message || `连接失败（${response.status}）`);
    }
    session = payload.data || payload;
    if (!session.runtimeToken || !session.streamUrl) throw new Error("服务端没有返回有效会话");
    roleName.textContent = session.publicMetadata?.agent_name || session.agentId;
    const socket = new WebSocket(session.streamUrl, [`bearer.${session.runtimeToken}`]);
    conversation = socket;
    socket.addEventListener("message", (event) => {
      if (conversation !== socket) return;
      if (typeof event.data !== "string") return;
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
  addMessage("user", text);
  conversation.send(JSON.stringify({ type: "turn.text", request_id: crypto.randomUUID(), text }));
  textInput.value = "";
}

function downsample(buffer, inputRate, outputRate) {
  if (inputRate === outputRate) return buffer;
  const ratio = inputRate / outputRate;
  const length = Math.round(buffer.length / ratio);
  const result = new Float32Array(length);
  let offsetResult = 0;
  let offsetBuffer = 0;
  while (offsetResult < result.length) {
    const nextOffsetBuffer = Math.round((offsetResult + 1) * ratio);
    let accum = 0;
    let count = 0;
    for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i += 1) {
      accum += buffer[i];
      count += 1;
    }
    result[offsetResult] = count ? accum / count : 0;
    offsetResult += 1;
    offsetBuffer = nextOffsetBuffer;
  }
  return result;
}

function encodePcm(chunks, sampleRate) {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const pcm = new Int16Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    for (let i = 0; i < chunk.length; i += 1) pcm[offset + i] = Math.max(-1, Math.min(1, chunk[i])) * 0x7fff;
    offset += chunk.length;
  }
  return { pcm: new Uint8Array(pcm.buffer), durationMs: Math.round((total / sampleRate) * 1000) };
}

async function startRecording() {
  if (!conversation || conversation.readyState !== WebSocket.OPEN || !navigator.mediaDevices) return;
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
    recordButton.classList.add("recording");
    recordButton.innerHTML = '<span aria-hidden="true">●</span> 停止录音';
    setStatus("busy", "正在录音");
  } catch (error) {
    mediaStream?.getTracks().forEach((track) => track.stop());
    mediaStream = null;
    audioContext = null;
    processor = null;
    setStatus("online", error.name === "NotAllowedError" ? "麦克风权限被拒绝" : "无法开始录音");
  }
}

async function stopRecording() {
  if (!processor || !audioContext) return;
  processor.disconnect();
  mediaStream?.getTracks().forEach((track) => track.stop());
  const { pcm, durationMs } = encodePcm(pcmChunks, 16000);
  conversation.send(JSON.stringify({ type: "turn.audio.start", request_id: crypto.randomUUID(), duration_ms: Math.max(durationMs, Math.round(performance.now() - recordingStartedAt)) }));
  conversation.send(pcm.buffer);
  conversation.send(JSON.stringify({ type: "turn.audio.end" }));
  await audioContext.close();
  audioContext = null;
  processor = null;
  mediaStream = null;
  pcmChunks = [];
  recordButton.classList.remove("recording");
  recordButton.innerHTML = '<span aria-hidden="true">●</span> 录音';
}

document.querySelector("#connection-panel").addEventListener("submit", (event) => {
  event.preventDefault();
  connect();
});
sendButton.addEventListener("click", sendText);
textInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); sendText(); }
});
recordButton.addEventListener("click", () => {
  const operation = processor ? stopRecording() : startRecording();
  operation?.catch((error) => setStatus("online", error.message || "录音发送失败"));
});
document.addEventListener("pointerdown", () => {
  void audioPlayback.resume();
}, { passive: true });
window.addEventListener("beforeunload", () => {
  audioPlayback.dispose();
});
