import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import storage from "../utils/jsonStorage.js";
import premiumManager from "../modulos/premium/premiumManager.js";
import adultMode from "../modulos/premium/adultMode.js";
import verificacionEdad from "../auth/verificacionEdad.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import mercadoPago from "../api/mercadoPago.js";

delete process.env.MERCADO_PAGO_ACCESS_TOKEN;
const nuevoUsuario = () => usuariosMemoria.guardarUsuario(`prem_${crypto.randomBytes(5).toString("hex")}@example.com`, { tipoLogin: "google", googleId: crypto.randomUUID() });
const conEnv = async (vars, fn) => {
  const prev = Object.fromEntries(Object.keys(vars).map(k => [k, process.env[k]]));
  Object.entries(vars).forEach(([k, v]) => (v == null ? delete process.env[k] : (process.env[k] = v)));
  try { return await fn(); } finally { Object.entries(prev).forEach(([k, v]) => (v == null ? delete process.env[k] : (process.env[k] = v))); }
};

test("A3: el mismo paymentId no extiende Premium dos veces (verify y webhook)", async () => {
  const user = nuevoUsuario();
  verificacionEdad.guardar(user.id, "1990-01-15", "test");
  const pref = await mercadoPago.generarLinkPago(user.id, "Respaldo en la nube");
  const r1 = await mercadoPago.pagarMock(pref.preferenceId, "approved", user.id);
  const paymentId = premiumManager.obtenerEstado(user.id).historial.find(h => h.tipo === "pago_aprobado").paymentId;
  const hasta = premiumManager.obtenerEstado(user.id).premiumHasta;
  assert.equal(r1.premiumActivo, true);
  const r2 = await mercadoPago.verificarPago(paymentId, user.id);
  const r3 = await mercadoPago.procesarWebhook({ type: "payment", data: { id: paymentId } }, {});
  assert.equal(r2.yaProcesado, true);
  assert.equal(r3.yaProcesado, true);
  assert.equal(premiumManager.obtenerEstado(user.id).premiumHasta, hasta, "premiumHasta no cambia");
  assert.equal(premiumManager.obtenerEstado(user.id).historial.filter(h => h.tipo === "pago_aprobado").length, 1);
  // Otro usuario no puede reclamar el mismo pago.
  const otro = nuevoUsuario();
  await assert.rejects(() => mercadoPago.verificarPago(paymentId, otro.id), err => [403, 409].includes(err.status));
});

test("A3: activarPremium es idempotente y cuenta desde la aprobación", () => {
  const id = `prem-idem-${crypto.randomBytes(4).toString("hex")}`;
  const aprobado = new Date(Date.now() - 10 * 24 * 3600e3).toISOString();
  const a = premiumManager.activarPremium(id, "pay-1", { aprobadoEn: aprobado });
  const b = premiumManager.activarPremium(id, "pay-1", { aprobadoEn: new Date().toISOString() });
  assert.equal(b.yaProcesado, true);
  assert.equal(b.premiumHasta, a.premiumHasta);
  assert.ok(Math.abs(Date.parse(a.premiumHasta) - (Date.parse(aprobado) + 30 * 24 * 3600e3)) < 5000);
});

test("A4: el checkout REST exige mayoría de edad verificada; el Modo Adulto también", async () => {
  const sinDato = nuevoUsuario();
  await assert.rejects(() => mercadoPago.generarLinkPago(sinDato.id, "Modo Adulto"), { status: 403 });
  const menor = nuevoUsuario();
  verificacionEdad.guardar(menor.id, new Date(Date.now() - 15 * 365 * 24 * 3600e3).toISOString().slice(0, 10), "test");
  await assert.rejects(() => mercadoPago.generarLinkPago(menor.id, "Modo Adulto"), { status: 403 });
  premiumManager.activarPremium(menor.id, `pay-${crypto.randomUUID()}`, {});
  assert.throws(() => adultMode.habilitarExtension(menor.id), err => err.status === 403);
  const mayor = nuevoUsuario();
  verificacionEdad.guardar(mayor.id, "1990-01-15", "test");
  assert.ok((await mercadoPago.generarLinkPago(mayor.id, "Modo Adulto")).preferenceId);
});

test("M6: sin token en producción no hay mock (checkout 503, pagar mock 403)", async () => {
  await conEnv({ NODE_ENV: "production", MERCADO_PAGO_ACCESS_TOKEN: null }, async () => {
    assert.equal(mercadoPago.modoMock(), false);
    const user = nuevoUsuario();
    verificacionEdad.guardar(user.id, "1990-01-15", "test");
    await assert.rejects(() => mercadoPago.generarLinkPago(user.id, "M/A"), { status: 503 });
    await assert.rejects(() => mercadoPago.pagarMock("mock-pref-x", "approved", user.id), { status: 403 });
  });
});

test("webhook: firma x-signature obligatoria con secreto; sin secreto se rechaza en producción", async () => {
  const secret = "s3cr3t-test";
  const dataId = "123456";
  const ts = "1700000000";
  const requestId = "req-1";
  const v1 = crypto.createHmac("sha256", secret).update(`id:${dataId};request-id:${requestId};ts:${ts};`).digest("hex");
  await conEnv({ MERCADO_PAGO_WEBHOOK_SECRET: secret }, async () => {
    assert.equal(mercadoPago.verificarFirmaWebhook({ headers: { "x-signature": `ts=${ts},v1=${v1}`, "x-request-id": requestId }, query: { "data.id": dataId } }).verificado, true);
    assert.throws(() => mercadoPago.verificarFirmaWebhook({ headers: { "x-signature": `ts=${ts},v1=${"0".repeat(64)}`, "x-request-id": requestId }, query: { "data.id": dataId } }), { status: 401 });
    await assert.rejects(() => mercadoPago.procesarWebhook({ type: "payment", data: { id: dataId } }, { "data.id": dataId }, {}), { status: 401 });
  });
  await conEnv({ MERCADO_PAGO_WEBHOOK_SECRET: null, NODE_ENV: "production" }, async () => {
    assert.throws(() => mercadoPago.verificarFirmaWebhook({ headers: {}, query: { "data.id": dataId } }), { status: 401 });
  });
});

test("monto/moneda: un pago real que no corresponde al plan no activa Premium", async () => {
  const user = nuevoUsuario();
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ id: 99, status: "approved", currency_id: "USD", transaction_amount: 1, metadata: { userId: user.id }, external_reference: user.id }), { status: 200 });
  try {
    await conEnv({ MERCADO_PAGO_ACCESS_TOKEN: "TEST-token" }, async () => {
      await assert.rejects(() => mercadoPago.verificarPago("99", user.id), { status: 422 });
    });
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.equal(premiumManager.obtenerEstado(user.id).premiumActivo, false);
  assert.equal(storage.readUserData("mercadopago_pagos", "99", null), null);
});

test("M5: el alcance Premium no promete que el servidor no pueda leer el respaldo", async () => {
  const { ALCANCE_PREMIUM } = await import("../modulos/premium/premiumManager.js");
  assert.ok(!/no puede leerla/.test(ALCANCE_PREMIUM[0]));
  assert.match(ALCANCE_PREMIUM[0], /no es cifrado de extremo a extremo/);
});
