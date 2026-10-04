import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import login, { hashToken, MAX_SESIONES_POR_USUARIO, _recargarSesionesDesdeDisco } from "../auth/login.js";
import { sincronizarPerfil } from "../auth/googleAuth.js";
import storage from "../utils/jsonStorage.js";

const DIA = 24 * 60 * 60 * 1000;

function usuarioGoogleNuevo(etiqueta) {
  const email = `persistente_${etiqueta}_${crypto.randomBytes(4).toString("hex")}@example.com`;
  const usuario = sincronizarPerfil({ sub: `sub-${crypto.randomBytes(6).toString("hex")}`, email, email_verified: true, name: "Persi" });
  const sesion = login.iniciarSesionParaUsuario(email);
  assert.equal(sesion.ok, true);
  return { usuario, token: sesion.token, sesion };
}

function conRelojAdelantado(ms, fn) {
  const original = Date.now;
  Date.now = () => original() + ms;
  try { return fn(); } finally { Date.now = original; }
}

test("sesión: no vence por tiempo (ni a los 8 días ni a los 3 años) y no informa vencimiento", () => {
  const { usuario, token, sesion } = usuarioGoogleNuevo("tiempo");
  assert.equal(sesion.expiraEn, null);
  for (const dias of [8, 31, 400, 3 * 365]) {
    const auth = conRelojAdelantado(dias * DIA, () => login.validarToken(token));
    assert.equal(auth?.userId, usuario.id, `sigue válida a los ${dias} días`);
    assert.equal(auth.expira, null);
  }
});

test("sesión: sobrevive al reinicio del servidor y revive las guardadas con el vencimiento viejo de 7 días", () => {
  const { usuario, token } = usuarioGoogleNuevo("reinicio");
  // Formato anterior en disco: { expira } ya vencido. La regla nueva: sigue valiendo hasta el logout.
  const archivo = path.join(storage.DATA_DIR, "sesiones_auth", "tokens.json");
  const data = JSON.parse(fs.readFileSync(archivo, "utf8"));
  const h = hashToken(token);
  data.tokens[h] = { ...data.tokens[h], expira: Date.now() - 30 * DIA };
  fs.writeFileSync(archivo, JSON.stringify(data));
  _recargarSesionesDesdeDisco();
  assert.equal(login.validarToken(token)?.userId, usuario.id);
  const migrado = JSON.parse(fs.readFileSync(archivo, "utf8")).tokens[h];
  assert.ok(migrado, "sigue en disco");
  assert.equal("expira" in migrado, false, "se migró sin vencimiento");
  assert.ok(!fs.readFileSync(archivo, "utf8").includes(token), "el token nunca queda en claro");
});

test("sesión: solo el logout explícito la revoca", () => {
  const { token } = usuarioGoogleNuevo("logout");
  assert.ok(login.validarToken(token));
  assert.equal(login.cerrarSesion(token).ok, true);
  assert.equal(login.validarToken(token), null);
  _recargarSesionesDesdeDisco();
  assert.equal(login.validarToken(token), null, "tampoco revive tras reiniciar");
});

test("sesión: re-logins silenciosos no acumulan sesiones sin límite (se conserva la más reciente)", () => {
  const { usuario, token: primera } = usuarioGoogleNuevo("poda");
  let ultima = primera;
  for (let i = 0; i < MAX_SESIONES_POR_USUARIO + 2; i++) {
    ultima = conRelojAdelantado((i + 1) * 1000, () => login.iniciarSesionParaUsuario(usuario.email).token);
  }
  const vivas = login.sesionesActivas().filter(s => s.userId === usuario.id);
  assert.equal(vivas.length, MAX_SESIONES_POR_USUARIO);
  assert.equal(login.validarToken(ultima)?.userId, usuario.id);
  assert.equal(login.validarToken(primera), null, "la más vieja se descartó");
});

test("HTTP: token viejo sigue autenticando /api/auth/me; tokens inválidos → 401; logout → 401 después", async () => {
  const { usuario, token } = usuarioGoogleNuevo("http");
  const { default: app } = await import("../server.js");
  const server = app.listen(0);
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    const me = t => fetch(`${url}/api/auth/me`, { headers: t ? { Authorization: `Bearer ${t}` } : {} });
    const original = Date.now;
    Date.now = () => original() + 30 * DIA;
    try {
      const r = await me(token);
      assert.equal(r.status, 200);
      assert.equal((await r.json()).data.userId, usuario.id);
    } finally { Date.now = original; }
    assert.equal((await me(null)).status, 401);
    assert.equal((await me("x".repeat(96))).status, 401);
    assert.equal((await me(hashToken(token))).status, 401);
    const out = await fetch(`${url}/api/auth/logout`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    assert.equal(out.status, 200);
    assert.equal((await me(token)).status, 401);
  } finally {
    server.close();
  }
});
