import assert from "node:assert/strict";
import test from "node:test";

import { createConversationTurnStore } from "../docs/api/public-conversation-turn-model.js";

test("keeps each user turn directly before its assistant reply", () => {
  const store = createConversationTurnStore();
  const first = store.ensure({ requestId: "r1", inputMode: "audio" });
  const second = store.ensure({ requestId: "r2", inputMode: "text" });

  store.bindTurnId("r1", "t1");
  store.bindTurnId("r2", "t2");
  store.setAsr("t1", "你好");
  store.appendAssistantText("t1", "收到");
  store.appendAssistantText("t2", "第二轮");

  assert.equal(first.turnId, "t1");
  assert.equal(second.turnId, "t2");
  assert.deepEqual(store.list().map((turn) => [turn.user.requestId, turn.assistant.text]), [
    ["r1", "收到"],
    ["r2", "第二轮"],
  ]);
  assert.equal(store.get("t1").user.asrText, "你好");
});

test("updates an audio turn without creating a second user message", () => {
  const store = createConversationTurnStore();
  store.ensure({ requestId: "r1", inputMode: "audio", audioUrl: "blob:user" });
  store.bindTurnId("r1", "t1");
  store.setAsr("t1", "测试语音");
  store.setAssistantAudio("t1", "blob:assistant");

  const turn = store.get("t1");
  assert.equal(store.list().length, 1);
  assert.equal(turn.user.audioUrl, "blob:user");
  assert.equal(turn.user.asrText, "测试语音");
  assert.equal(turn.assistant.audioUrl, "blob:assistant");
});

test("finds an audio turn by request ID before the server binds its turn ID", () => {
  const store = createConversationTurnStore();
  const turn = store.ensure({ requestId: "r1", inputMode: "audio" });

  assert.equal(store.get("r1"), turn);
});
