#!/usr/bin/env node

const apiBase = (process.env.PUBLIC_API_BASE || "http://127.0.0.1:8002/xiaozhi").replace(/\/$/, "");
const authorization = process.env.PUBLIC_AUTHORIZATION;
const agentId = process.env.PUBLIC_AGENT_ID;
const prompt = process.env.PUBLIC_PROMPT || "你好，这是公共接口冒烟测试。";
const timeoutMs = Number(process.env.PUBLIC_SMOKE_TIMEOUT_MS || 60_000);

if (!authorization || !agentId) {
  console.error("需要设置 PUBLIC_AUTHORIZATION 和 PUBLIC_AGENT_ID");
  process.exit(2);
}

const deadline = Date.now() + timeoutMs;
const response = await fetch(`${apiBase}/api/v1/conversations`, {
  method: "POST",
  headers: { Authorization: authorization, "Content-Type": "application/json" },
  body: JSON.stringify({ agentId, inputModes: ["text"], outputModes: ["text"] }),
});
const payload = await response.json().catch(() => ({}));
if (!response.ok) {
  throw new Error(`会话创建失败: HTTP ${response.status}`);
}

const session = payload.data || payload;
if (!session.streamUrl || !session.runtimeToken) {
  throw new Error("会话响应缺少 streamUrl 或 runtimeToken");
}

const socket = new WebSocket(session.streamUrl, [`bearer.${session.runtimeToken}`]);
const events = [];
const waitFor = new Promise((resolve, reject) => {
  const timer = setInterval(() => {
    if (Date.now() > deadline) {
      clearInterval(timer);
      reject(new Error("公共会话冒烟测试超时"));
    }
  }, 250);

  socket.addEventListener("open", () => {
    socket.send(JSON.stringify({ type: "turn.text", request_id: `smoke-${Date.now()}`, text: prompt }));
  });
  socket.addEventListener("message", (event) => {
    if (typeof event.data !== "string") return;
    const item = JSON.parse(event.data);
    events.push(item);
    if (item.type === "turn.completed") {
      clearInterval(timer);
      resolve(item);
    }
    if (item.type === "error") {
      clearInterval(timer);
      reject(new Error(`公共会话失败: ${item.details?.code || "unknown"}`));
    }
  });
  socket.addEventListener("error", () => {
    clearInterval(timer);
    reject(new Error("公共会话 WebSocket 连接失败"));
  });
});

try {
  const completed = await waitFor;
  console.log(JSON.stringify({
    conversationId: session.conversationId,
    agentId: session.agentId,
    agentVersion: session.agentVersion,
    eventTypes: events.map((item) => item.type),
    completed: completed.type === "turn.completed",
  }));
} finally {
  socket.close();
}
