#!/usr/bin/env node

const apiBase = (process.env.PUBLIC_API_BASE || "http://127.0.0.1:8002/xiaozhi").replace(/\/$/, "");
const authorization = process.env.PUBLIC_AUTHORIZATION;
const agentId = process.env.PUBLIC_AGENT_ID;

if (!authorization || !agentId) {
  console.error("需要设置 PUBLIC_AUTHORIZATION 和 PUBLIC_AGENT_ID");
  process.exit(2);
}

async function request(path, options = {}) {
  const response = await fetch(`${apiBase}${path}`, {
    ...options,
    headers: { Authorization: authorization, "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.code && payload.code !== 0) {
    throw new Error(payload.msg || payload.message || `HTTP ${response.status}`);
  }
  return payload.data;
}

const profile = await request(`/companion/profiles/${encodeURIComponent(agentId)}`);
const currentSkills = Array.isArray(profile.skills) ? profile.skills : [];
console.log(JSON.stringify({
  agentId,
  activeVersionNo: profile.activeVersionNo,
  existingSkillIds: currentSkills.map((skill) => skill.skillId),
}));

const readonlySkills = [
  { skillId: "skill-weather", versionMode: "LATEST", fixedVersion: null, overrideJson: null, triggerPriority: 100, enabled: true },
  { skillId: "skill-news", versionMode: "LATEST", fixedVersion: null, overrideJson: null, triggerPriority: 90, enabled: true },
];
const mergedSkills = [...currentSkills.filter((skill) => !readonlySkills.some((item) => item.skillId === skill.skillId)), ...readonlySkills];
const payload = {
  agentName: profile.name,
  relationMode: profile.relationMode,
  userAddress: profile.userAddress,
  personality: profile.personality,
  systemPrompt: profile.systemPrompt,
  companionCueConfig: profile.companionCueConfig,
  screenExpressionEnabled: profile.screenExpressionEnabled,
  cameraPreferenceEnabled: profile.cameraPreferenceEnabled,
  ttsVoiceId: profile.ttsVoiceId,
  models: (profile.models || []).map((model) => ({
    modelType: model.modelType,
    source: model.source,
    resourceId: model.resourceId,
  })),
  skills: mergedSkills.map((skill) => ({
    skillId: skill.skillId,
    versionMode: skill.versionMode || "LATEST",
    fixedVersion: skill.fixedVersion ?? null,
    overrideJson: skill.overrideJson ?? null,
    triggerPriority: skill.triggerPriority ?? 0,
    enabled: skill.enabled !== false,
  })),
};

await request(`/companion/profiles/${encodeURIComponent(agentId)}`, {
  method: "PUT",
  body: JSON.stringify(payload),
});
const published = await request(`/agent/${encodeURIComponent(agentId)}/snapshots/publish`, { method: "POST" });
const snapshots = await request(`/agent/${encodeURIComponent(agentId)}/snapshots?page=1&limit=1`);
const latestSnapshot = snapshots?.list?.[0] || snapshots?.records?.[0];
if (!latestSnapshot?.id) throw new Error("发布后没有找到新的 Agent 版本");
await request(`/agent/${encodeURIComponent(agentId)}/snapshots/${encodeURIComponent(latestSnapshot.id)}/activate`, { method: "POST" });
const updated = await request(`/companion/profiles/${encodeURIComponent(agentId)}`);
console.log(JSON.stringify({
  agentId,
  activeVersionNo: updated.activeVersionNo,
  boundSkillIds: (updated.skills || []).filter((skill) => skill.enabled !== false).map((skill) => skill.skillId),
  publishedVersion: published?.versionNo || published?.version || latestSnapshot.versionNo || updated.activeVersionNo,
}));
