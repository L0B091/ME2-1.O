import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import verificacionEdad from "../auth/verificacionEdad.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import mercadoPago from "../api/mercadoPago.js";

// Mercado Pago 100% por entorno: sin variables no hay URLs inventadas ni túneles.
const VARS = ["MERCADO_PAGO_ACCESS_TOKEN", "BACKEND_PUBLIC_URL", "MERCADO_PAGO_SUCCESS_URL",
  "MERCADO_PAGO_PENDING_URL", "MERCADO_PAGO_FAILURE_URL", "NODE_ENV"];
const conEnv = async (vars, fn) => {
  const prev = Object.fromEntries(VARS.map(k => [k, process.env[k]]));
  VARS.forEach(k => delete process.env[k]);
  Object.entries(vars).forEach(([k, v]) => { if (v != null) process.env[k] = v; });
  try { return await fn(); } finally { VARS.forEach(k => (prev[k] == null ? delete process.env[k] : (process.env[k] = prev[k]))); }
};

test("MP config: sin env no hay notification_url, back_urls ni auto_return", () => conEnv({}, () => {
  assert.deepEqual(mercadoPago.urlsPreferencia(), { back_urls: undefined, auto_return: undefined, notification_url: undefined });
}));

test("MP config: webhook = <BACKEND_PUBLIC_URL>/api/mercadopago/webhook y back_urls desde env", () => conEnv({
  BACKEND_PUBLIC_URL: " https://api.mi-dominio.com/ ",
  MERCADO_PAGO_SUCCESS_URL: "https://mi-dominio.com/pago/ok",
  MERCADO_PAGO_PENDING_URL: "https://mi-dominio.com/pago/pendiente",
  MERCADO_PAGO_FAILURE_URL: "https://mi-dominio.com/pago/error"
}, () => {
  const u = mercadoPago.urlsPreferencia();
  assert.equal(u.notification_url, "https://api.mi-dominio.com/api/mercadopago/webhook");
  assert.equal(u.auto_return, "approved");
  assert.deepEqual(u.back_urls, { success: "https://mi-dominio.com/pago/ok", pending: "https://mi-dominio.com/pago/pendiente", failure: "https://mi-dominio.com/pago/error" });
}));

test("MP config: back_urls incompletas → sin back_urls ni auto_return (MP rechazaría la preferencia)", () => conEnv({
  MERCADO_PAGO_SUCCESS_URL: "https://mi-dominio.com/pago/ok"
}, () => {
  const u = mercadoPago.urlsPreferencia();
  assert.equal(u.back_urls, undefined);
  assert.equal(u.auto_return, undefined);
}));

test("MP config: modo mock solo sin token y fuera de producción", () => conEnv({}, async () => {
  assert.equal(mercadoPago.modoMock(), true);
  process.env.NODE_ENV = "production";
  assert.equal(mercadoPago.modoMock(), false);
  process.env.NODE_ENV = "test";
  process.env.MERCADO_PAGO_ACCESS_TOKEN = "TEST-token";
  assert.equal(mercadoPago.modoMock(), false);
}));

test("MP config: en producción sin token el checkout responde 503 (deshabilitado, sin mock)", () => conEnv({ NODE_ENV: "production" }, async () => {
  const user = usuariosMemoria.guardarUsuario(`mpcfg_${crypto.randomBytes(5).toString("hex")}@example.com`, { tipoLogin: "google", googleId: crypto.randomUUID() });
  verificacionEdad.guardar(user.id, "1990-01-15", "test");
  await assert.rejects(mercadoPago.generarLinkPago(user.id, "M/A"), e => e.status === 503);
}));

test("MP config: con token la preferencia lleva las URLs del entorno", () => conEnv({
  MERCADO_PAGO_ACCESS_TOKEN: "TEST-token",
  BACKEND_PUBLIC_URL: "https://api.mi-dominio.com"
}, async () => {
  const user = usuariosMemoria.guardarUsuario(`mpcfg_${crypto.randomBytes(5).toString("hex")}@example.com`, { tipoLogin: "google", googleId: crypto.randomUUID() });
  verificacionEdad.guardar(user.id, "1990-01-15", "test");
  const fetchOriginal = globalThis.fetch;
  let enviado = null;
  globalThis.fetch = async (url, opts) => {
    enviado = { url: String(url), body: JSON.parse(opts.body), auth: opts.headers.Authorization };
    return new Response(JSON.stringify({ id: "pref-123", init_point: "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=pref-123" }), { status: 201 });
  };
  try {
    const r = await mercadoPago.generarLinkPago(user.id, "M/A");
    assert.equal(r.preferenceId, "pref-123");
  } finally { globalThis.fetch = fetchOriginal; }
  assert.equal(enviado.url, "https://api.mercadopago.com/checkout/preferences");
  assert.equal(enviado.auth, "Bearer TEST-token");
  assert.equal(enviado.body.notification_url, "https://api.mi-dominio.com/api/mercadopago/webhook");
  assert.equal("auto_return" in enviado.body, false);
  assert.equal("back_urls" in enviado.body, false);
}));
