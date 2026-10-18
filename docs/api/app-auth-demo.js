// App 认证面板：在本地操练场验证 /app/v1/auth 接口。
// 登录成功后把 accessToken 填入下方"用户令牌"输入框，即可继续连接角色对话。
// 密码通过服务端 SM2 公钥加密传输（与 /user/pub-config 的 sm2PublicKey 配对）。

const STORAGE_KEY = "appAuthDemoSession";

const phoneInput = document.querySelector("#auth-phone");
const codeInput = document.querySelector("#auth-code");
const passwordInput = document.querySelector("#auth-password");
const sendCodeButton = document.querySelector("#auth-send-code");
const loginCodeButton = document.querySelector("#auth-login-code");
const loginPasswordButton = document.querySelector("#auth-login-password");
const registerButton = document.querySelector("#auth-register");
const refreshButton = document.querySelector("#auth-refresh");
const logoutButton = document.querySelector("#auth-logout");
const statusText = document.querySelector("#auth-status");
const profileText = document.querySelector("#auth-profile");
const authorizationInput = document.querySelector("#authorization");
const apiBaseInput = document.querySelector("#api-base");

let sm2PublicKeyCache = null;
let resendTimer = null;

function apiBase() {
  const base = (apiBaseInput.value || "http://127.0.0.1:8002/zixuan").trim();
  return base.replace(/\/+$/, "");
}

function readStoredSession() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
  } catch {
    return null;
  }
}

function persistSession(session) {
  if (session) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function setStatus(message, isError = false) {
  statusText.textContent = message;
  statusText.classList.toggle("auth-status-error", isError);
}

async function requestJson(path, options = {}) {
  const response = await fetch(apiBase() + path, options);
  const body = await response.json().catch(() => ({ code: response.status, msg: "响应不是 JSON" }));
  if (body.code !== 0) {
    throw new Error(`[${body.code}] ${body.msg || "请求失败"}`);
  }
  return body.data;
}

async function fetchSm2PublicKey() {
  if (sm2PublicKeyCache) return sm2PublicKeyCache;
  const data = await requestJson("/user/pub-config");
  const key = String(data.sm2PublicKey || "").trim();
  if (!/^(04)?[0-9a-fA-F]{128}$/.test(key)) {
    throw new Error("服务端 SM2 公钥格式无效，请检查接口地址");
  }
  sm2PublicKeyCache = key.startsWith("04") ? key : "04" + key;
  return sm2PublicKeyCache;
}

async function encryptPassword(plain) {
  const publicKey = await fetchSm2PublicKey();
  const cipher = window.sm2.doEncrypt(plain, publicKey, 1);
  if (!/^[0-9a-f]+$/i.test(cipher)) {
    throw new Error("SM2 加密失败");
  }
  return "04" + cipher;
}

function requirePhone() {
  const raw = phoneInput.value.trim();
  const compact = raw.replace(/[\s-]/g, "");
  // 国内 11 位、+86 前缀或其他国际格式均可，服务端会统一归一化
  if (/^1[3-9]\d{9}$/.test(compact) || /^\+[1-9]\d{5,19}$/.test(compact)) {
    return compact;
  }
  throw new Error("手机号格式不正确，例如 13800138000 或 +8613800138000");
}

function onSessionIssued(data, label) {
  const session = {
    phone: phoneInput.value.trim(),
    accessToken: data.accessToken,
    refreshToken: data.refreshToken,
    expiresAt: Date.now() + data.expiresIn * 1000,
  };
  persistSession(session);
  authorizationInput.value = "Bearer " + data.accessToken;
  renderSession(label);
}

function renderSession(prefix = "") {
  const session = readStoredSession();
  if (!session) {
    profileText.textContent = "未登录";
    logoutButton.disabled = true;
    refreshButton.disabled = true;
    return;
  }
  const remainMinutes = Math.max(0, Math.round((session.expiresAt - Date.now()) / 60000));
  profileText.textContent =
    `${prefix}${session.phone} · 访问令牌剩余约 ${remainMinutes} 分钟（令牌已填入「用户令牌」输入框）`;
  logoutButton.disabled = false;
  refreshButton.disabled = false;
  // 页面刷新后恢复会话：输入框为空时回填，避免覆盖手动填写的其他凭证
  if (!authorizationInput.value.trim() && session.expiresAt > Date.now()) {
    authorizationInput.value = "Bearer " + session.accessToken;
  }
}

async function guard(action) {
  try {
    await action();
  } catch (error) {
    setStatus(error.message, true);
  }
}

sendCodeButton.addEventListener("click", () => guard(async () => {
  const phone = requirePhone();
  sendCodeButton.disabled = true;
  let seconds = 60;
  sendCodeButton.textContent = `${seconds}s`;
  resendTimer = setInterval(() => {
    seconds -= 1;
    if (seconds <= 0) {
      clearInterval(resendTimer);
      sendCodeButton.disabled = false;
      sendCodeButton.textContent = "发送验证码";
    } else {
      sendCodeButton.textContent = `${seconds}s`;
    }
  }, 1000);
  try {
    await requestJson("/app/v1/auth/sms-code", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ phone }),
    });
    setStatus(`验证码已发送至 ${phone}（本地联调可用 redis-cli 写入测试验证码）`);
  } catch (error) {
    clearInterval(resendTimer);
    sendCodeButton.disabled = false;
    sendCodeButton.textContent = "发送验证码";
    throw error;
  }
}));

loginCodeButton.addEventListener("click", () => guard(async () => {
  const phone = requirePhone();
  const code = codeInput.value.trim();
  if (!code) throw new Error("请输入短信验证码");
  const data = await requestJson("/app/v1/auth/login-code", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone, code }),
  });
  onSessionIssued(data, "验证码登录成功 · ");
  setStatus("验证码登录成功，可点击下方「连接角色」开始对话");
}));

loginPasswordButton.addEventListener("click", () => guard(async () => {
  const phone = requirePhone();
  const password = passwordInput.value;
  if (!password) throw new Error("请输入密码");
  const encrypted = await encryptPassword(password);
  const data = await requestJson("/app/v1/auth/login-password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone, password: encrypted }),
  });
  onSessionIssued(data, "密码登录成功 · ");
  setStatus("密码登录成功，可点击下方「连接角色」开始对话");
}));

registerButton.addEventListener("click", () => guard(async () => {
  const phone = requirePhone();
  const code = codeInput.value.trim();
  const password = passwordInput.value;
  if (!code) throw new Error("请输入短信验证码");
  if (!password) throw new Error("请输入密码（需包含大小写字母和数字）");
  const encrypted = await encryptPassword(password);
  const data = await requestJson("/app/v1/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone, code, password: encrypted }),
  });
  onSessionIssued(data, "注册成功 · ");
  setStatus("注册成功并已登录");
}));

refreshButton.addEventListener("click", () => guard(async () => {
  const session = readStoredSession();
  if (!session) throw new Error("没有可刷新的会话，请先登录");
  const data = await requestJson("/app/v1/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  });
  onSessionIssued({ ...data, refreshToken: session.refreshToken }, "令牌已刷新 · ");
  setStatus("访问令牌已刷新并回填");
}));

logoutButton.addEventListener("click", () => guard(async () => {
  const session = readStoredSession();
  if (!session) return;
  await requestJson("/app/v1/auth/logout", {
    method: "POST",
    headers: { Authorization: "Bearer " + session.accessToken },
  });
  persistSession(null);
  authorizationInput.value = "";
  renderSession();
  setStatus("已退出当前会话，令牌已失效");
}));

renderSession();
setStatus("填入手机号后可发送验证码，或直接使用密码登录");
