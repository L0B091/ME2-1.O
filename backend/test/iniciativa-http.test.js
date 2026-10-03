import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";

test("native beta evaluation endpoint works without Google, payment, cloud DB or provider keys (anónimo solo con ME2_ALLOW_ANONYMOUS=true)", async () => {
  const child = spawn(process.execPath, ["server.js"], {
    cwd: fileURLToPath(new URL("../", import.meta.url)),
    env: { ...process.env, PORT: "0", OPENROUTER_API_KEY: "", NEWS_API_KEY: "", GOOGLE_AUTH_ENABLED: "false", ME2_ALLOW_ANONYMOUS: "true" },
    stdio: ["ignore", "pipe", "pipe"]
  });
  const exited = once(child, "exit");
  let output = "";
  let errors = "";
  child.stderr.on("data", chunk => { errors += chunk; });
  try {
    const port = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Startup timeout: ${errors}`)), 15000);
      child.once("error", error => { clearTimeout(timeout); reject(error); });
      child.once("exit", code => { clearTimeout(timeout); reject(new Error(`Startup exit ${code}: ${errors}`)); });
      child.stdout.on("data", chunk => {
        output += chunk;
        const match = output.match(/http:\/\/localhost:(\d+)/);
        if (match) { clearTimeout(timeout); resolve(Number(match[1])); }
      });
    });
    const url = `http://127.0.0.1:${port}`;
    const health = await fetch(`${url}/health`);
    assert.equal(health.status, 200);
    const body = {
      userId: "local-http-test",
      memoriaLocal: { recentConversation: [], importantMemories: [], persistentMemories: [] },
      registro: [], perfilRitmo: { zonaHoraria: "UTC", observaciones: [] },
      disponibilidad: { enPrimerPlano: false, notificacionesHabilitadas: true }, eventos: []
    };
    const request = (payload, authorization) => fetch(`${url}/api/iniciativas/evaluar`, {
      method: "POST", headers: { "Content-Type": "application/json", ...(authorization ? { Authorization: authorization } : {}) },
      body: JSON.stringify(payload), signal: AbortSignal.timeout(10000)
    });
    const response = await request(body);
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.ok, true);
    assert.equal(result.data.decision, "ESPERAR");
    assert.equal(result.data.iniciativa, undefined);
    assert.equal((await request({ ...body, perfilRitmo: { zonaHoraria: "invalid" } })).status, 400);
    assert.equal((await request(body, "Bearer expired")).status, 401);
  } finally {
    child.kill();
    await exited;
  }
});
