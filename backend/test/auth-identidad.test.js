import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import login from "../auth/login.js";
import { sincronizarPerfil } from "../auth/googleAuth.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";

const emailUnico = () => `victima_${crypto.randomBytes(5).toString("hex")}@example.com`;
const subUnico = () => `sub-${crypto.randomBytes(6).toString("hex")}`;

test("C1: Google exige email_verified", () => {
  for (const ev of [false, undefined, "false"]) {
    assert.throws(() => sincronizarPerfil({ sub: subUnico(), email: emailUnico(), email_verified: ev }), err => err.status === 403);
  }
  assert.ok(sincronizarPerfil({ sub: subUnico(), email: emailUnico(), email_verified: "true" }).id);
});

test("C1: una cuenta local previa con el mismo email NO se fusiona con Google (pre-secuestro)", () => {
  const email = emailUnico();
  const atacante = login.registrarUsuario(email, "clave-del-atacante", { displayName: "Atacante" });
  assert.equal(atacante.ok, true);
  const idAtacante = atacante.perfil.userId;
  const tokenAtacante = atacante.token;
  assert.ok(login.validarToken(tokenAtacante));

  const sub = subUnico();
  const victima = sincronizarPerfil({ sub, email, email_verified: true, name: "Víctima" });
  assert.notEqual(victima.id, idAtacante, "la víctima recibe un id nuevo");
  assert.equal(victima.passwordHash, null);
  assert.equal(victima.passwordSalt, null);
  assert.equal(victima.googleId, sub);
  assert.equal(victima.displayName, "Víctima");

  // La contraseña del atacante ya no sirve y su token viejo tampoco resuelve a la cuenta de la víctima.
  assert.equal(login.loginUsuario(email, "clave-del-atacante").ok, false);
  assert.equal(login.validarToken(tokenAtacante), null);
});

test("C1: el mismo googleId conserva su id; otro googleId con el mismo email es rechazado", () => {
  const email = emailUnico();
  const sub = subUnico();
  const primero = sincronizarPerfil({ sub, email, email_verified: true, name: "Ana" });
  const segundo = sincronizarPerfil({ sub, email, email_verified: true, name: "Ana B" });
  assert.equal(segundo.id, primero.id);
  assert.equal(usuariosMemoria.obtenerUsuario(email).displayName, "Ana B");
  assert.throws(() => sincronizarPerfil({ sub: subUnico(), email, email_verified: true }), err => err.status === 409);
});

test("C1: /api/auth/register y /api/auth/login deshabilitados por defecto (Google único método)", async () => {
  delete process.env.LOCAL_AUTH_ENABLED;
  const { default: app } = await import("../server.js");
  const server = app.listen(0);
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    const post = (p, body) => fetch(`${url}${p}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    assert.equal((await post("/api/auth/register", { email: emailUnico(), password: "x" })).status, 404);
    assert.equal((await post("/api/auth/login", { email: emailUnico(), password: "x" })).status, 404);
  } finally {
    server.close();
  }
});
