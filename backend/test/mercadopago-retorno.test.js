import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import login from "../auth/login.js";
import { sincronizarPerfil } from "../auth/googleAuth.js";
import premiumManager from "../modulos/premium/premiumManager.js";

// Vuelta de Checkout Pro a la app (me2://pago?payment_id=…): la app llama POST /api/mercadopago/verify y el
// servidor consulta el pago en Mercado Pago. El estado que trae la URL nunca acredita nada por sí solo.
process.env.DOLPHIN_BASE_URL = "";
process.env.OPENROUTER_API_KEY = "";
delete process.env.ME2_ALLOW_ANONYMOUS;
for (const k of Object.keys(process.env)) if (k.startsWith("MERCADO_PAGO_")) delete process.env[k];
const { default: app } = await import("../server.js");

const RUN = String(crypto.randomInt(100000, 999999));
const P = n => `${RUN}${n}`;
function usuario(nombre) {
  const u = sincronizarPerfil({ sub: `sub-${crypto.randomBytes(6).toString("hex")}`, email: `${nombre}_${crypto.randomBytes(4).toString("hex")}@example.com`, email_verified: true, name: nombre });
  return { id: u.id, token: login.iniciarSesionParaUsuario(u.email).token };
}
async function conMercadoPago(pagos, fn) {
  const realFetch = globalThis.fetch;
  const prevToken = process.env.MERCADO_PAGO_ACCESS_TOKEN;
  process.env.MERCADO_PAGO_ACCESS_TOKEN = "TEST-token";
  const consultados = [];
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    if (!u.startsWith("https://api.mercadopago.com/")) return realFetch(url, opts);
    const id = u.split("/").pop();
    consultados.push(id);
    const p = pagos[id];
    return new Response(JSON.stringify(p || { message: "Payment not found" }), { status: p ? 200 : 404 });
  };
  const server = app.listen(0);
  try {
    return await fn(`http://127.0.0.1:${server.address().port}`, consultados);
  } finally {
    server.close();
    globalThis.fetch = realFetch;
    if (prevToken == null) delete process.env.MERCADO_PAGO_ACCESS_TOKEN; else process.env.MERCADO_PAGO_ACCESS_TOKEN = prevToken;
  }
}
const verify = (url, token, body) => fetch(`${url}/api/mercadopago/verify`, {
  method: "POST", headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body)
});
const pago = (id, userId, status, extra = {}) => ({ id: Number(id), status, currency_id: "ARS", transaction_amount: 3000, external_reference: userId, metadata: { user_id: userId }, date_approved: status === "approved" ? new Date().toISOString() : null, ...extra });

test("vuelta aprobada: verify consulta MP por payment_id y activa Premium con fecha de vencimiento", async () => {
  const u = usuario("vuelta_ok");
  await conMercadoPago({ [P(1)]: pago(P(1), u.id, "approved") }, async (url, consultados) => {
    const r = await verify(url, u.token, { paymentId: P(1) });
    assert.equal(r.status, 200);
    const body = await r.json();
    assert.equal(body.ok, true);
    assert.equal(body.data.premiumActivo, true);
    assert.ok(Date.parse(body.data.premiumHasta) > Date.now() + 29 * 24 * 3600e3, "≈30 días");
    assert.deepEqual(consultados, [P(1)], "se verificó contra Mercado Pago");
    // Volver dos veces (auto_return + botón) no extiende Premium dos veces.
    const otra = await (await verify(url, u.token, { paymentId: P(1) })).json();
    assert.equal(otra.data.yaProcesado, true);
    assert.equal(otra.data.premiumHasta, body.data.premiumHasta);
  });
});

test("vuelta pendiente: ok=false con status pending/in_process y Premium sin activar", async () => {
  const u = usuario("vuelta_pend");
  await conMercadoPago({ [P(2)]: pago(P(2), u.id, "pending"), [P(3)]: pago(P(3), u.id, "in_process") }, async url => {
    for (const [id, st] of [[P(2), "pending"], [P(3), "in_process"]]) {
      const body = await (await verify(url, u.token, { paymentId: id })).json();
      assert.equal(body.ok, false);
      assert.equal(body.data.status, st);
    }
    assert.equal(premiumManager.obtenerEstado(u.id).premiumActivo, false);
  });
});

test("vuelta fallida: rechazado → ok=false status rejected; Premium sin activar", async () => {
  const u = usuario("vuelta_rech");
  await conMercadoPago({ [P(4)]: pago(P(4), u.id, "rejected", { status_detail: "cc_rejected_other_reason" }) }, async url => {
    const body = await (await verify(url, u.token, { paymentId: P(4) })).json();
    assert.equal(body.ok, false);
    assert.equal(body.data.status, "rejected");
    assert.equal(premiumManager.obtenerEstado(u.id).premiumActivo, false);
  });
});

test("la URL de vuelta no alcanza: sin token 401, pago ajeno 403, inexistente 404, sin id 400", async () => {
  const duenio = usuario("vuelta_duenio");
  const otro = usuario("vuelta_otro");
  await conMercadoPago({ [P(5)]: pago(P(5), duenio.id, "approved") }, async url => {
    assert.equal((await verify(url, null, { paymentId: P(5) })).status, 401);
    assert.equal((await verify(url, otro.token, { paymentId: P(5) })).status, 403);
    assert.equal((await verify(url, otro.token, { paymentId: P(999) })).status, 404);
    assert.equal((await verify(url, otro.token, {})).status, 400);
    assert.equal(premiumManager.obtenerEstado(otro.id).premiumActivo, false);
    assert.equal(premiumManager.obtenerEstado(duenio.id).premiumActivo, false, "nadie lo acreditó todavía");
  });
});
