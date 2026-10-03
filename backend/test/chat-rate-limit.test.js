import test from "node:test";
import assert from "node:assert/strict";

process.env.ME2_CHAT_RATE_LIMIT = "2";
const { default: app } = await import("../server.js");

test("M7: /chat tiene rate limit por IP (429 al superar ME2_CHAT_RATE_LIMIT)", async () => {
  const server = app.listen(0);
  try {
    const url = `http://127.0.0.1:${server.address().port}/chat`;
    const send = () => fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ mensaje: "hola" }) });
    const estados = [];
    for (let i = 0; i < 3; i++) estados.push((await send()).status);
    assert.deepEqual(estados, [401, 401, 429]);
  } finally {
    server.close();
  }
});
