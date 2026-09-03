import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("silent verification executes manager-api tests", () => {
  const script = readFileSync(new URL("./verify-public-conversation-silent.sh", import.meta.url), "utf8");
  assert.match(script, /mvn[^\n]*-DskipTests=false[^\n]*-Dtest=/);
});
