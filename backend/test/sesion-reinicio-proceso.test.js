// Regla: una vez registrado, el usuario nunca vuelve a loguearse salvo cierre explícito. Las sesiones viven en disco
// (data/sesiones_auth, solo el hash del token) y deben seguir valiendo después de reiniciar el PROCESO del servidor
// (no solo de recargar el módulo), sin vencer por tiempo.
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function correrProceso(dataDir, codigo) {
  const r = spawnSync(process.execPath, ["--input-type=module", "-e", codigo], {
    cwd: backendDir,
    env: { ...process.env, ME2_DATA_DIR: dataDir },
    encoding: "utf8",
    timeout: 60_000
  });
  assert.equal(r.status, 0, `proceso hijo falló: ${r.stderr}`);
  const linea = r.stdout.trim().split("\n").filter(l => l.startsWith("{")).pop();
  return JSON.parse(linea);
}

test("sesión: sobrevive a reiniciar el proceso del servidor y no vence (HTTP /api/auth/me 200 años después)", () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "me2-sesion-reinicio-"));
  try {
    // Proceso 1: login con Google (perfil sincronizado) → token de ME2.
    const emitido = correrProceso(dataDir, `
      import login from "./auth/login.js";
      import { sincronizarPerfil } from "./auth/googleAuth.js";
      const u = sincronizarPerfil({ sub: "sub-reinicio-1", email: "reinicio@example.com", email_verified: true, name: "R" });
      const s = login.iniciarSesionParaUsuario("reinicio@example.com");
      console.log(JSON.stringify({ token: s.token, userId: u.id, expiraEn: s.expiraEn }));
      process.exit(0);
    `);
    assert.ok(emitido.token);
    assert.equal(emitido.expiraEn, null, "sin vencimiento");
    const enDisco = fs.readFileSync(path.join(dataDir, "sesiones_auth", "tokens.json"), "utf8");
    assert.ok(!enDisco.includes(emitido.token), "en disco solo queda el hash");

    // Proceso 2 (servidor reiniciado), con el reloj 5 años adelante: el mismo token sigue autenticando.
    const tras = correrProceso(dataDir, `
      const original = Date.now;
      Date.now = () => original() + 5 * 365 * 24 * 60 * 60 * 1000;
      const { default: app } = await import("./server.js");
      const server = app.listen(0);
      await new Promise(r => server.once("listening", r));
      const url = "http://127.0.0.1:" + server.address().port + "/api/auth/me";
      const r = await fetch(url, { headers: { Authorization: "Bearer " + ${JSON.stringify(emitido.token)} } });
      const body = await r.json().catch(() => ({}));
      server.close();
      console.log(JSON.stringify({ status: r.status, userId: body?.data?.userId ?? null }));
      process.exit(0);
    `);
    assert.equal(tras.status, 200);
    assert.equal(tras.userId, emitido.userId);

    // Proceso 3: logout explícito → y tras otro reinicio, el token ya no vale.
    const out = correrProceso(dataDir, `
      import login from "./auth/login.js";
      console.log(JSON.stringify({ ok: login.cerrarSesion(${JSON.stringify(emitido.token)}).ok }));
      process.exit(0);
    `);
    assert.equal(out.ok, true);
    const final = correrProceso(dataDir, `
      import login from "./auth/login.js";
      console.log(JSON.stringify({ auth: login.validarToken(${JSON.stringify(emitido.token)}) }));
      process.exit(0);
    `);
    assert.equal(final.auth, null, "solo el cierre explícito revoca, y queda revocado tras reiniciar");
  } finally {
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
