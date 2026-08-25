import assert from "node:assert/strict";
import test from "node:test";

import { createAudioSendQueue } from "../docs/api/public-conversation-send-queue.js";

test("keeps only configured active sends and drains in order", async () => {
  const sent = [];
  const queue = createAudioSendQueue({ maxActive: 2, send: async (item) => { sent.push(item.id); } });
  await Promise.all([queue.enqueue({ id: "a" }), queue.enqueue({ id: "b" }), queue.enqueue({ id: "c" })]);
  assert.deepEqual(sent, ["a", "b", "c"]);
  assert.equal(queue.snapshot().queued, 0);
  assert.equal(queue.snapshot().active, 0);
});
