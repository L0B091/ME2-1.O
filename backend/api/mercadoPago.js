import crypto from "crypto";
import premiumManager from "../modulos/premium/premiumManager.js";
import adultMode from "../modulos/premium/adultMode.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import HttpError from "../utils/httpError.js";
import storage from "../utils/jsonStorage.js";
import flujoPremium from "../modulos/premium/flujoPremium.js";
import verificacionEdad from "../auth/verificacionEdad.js";

// Sin MERCADO_PAGO_ACCESS_TOKEN: modo simulado (link y pago mock) SOLO fuera de producción.
// En producción sin token no hay mock: el checkout responde 503 (MERCADO_PAGO_ACCESS_TOKEN no configurado).
export function modoMock() {
  if (String(process.env.NODE_ENV || "").trim().toLowerCase() === "production") return false;
  return !String(process.env.MERCADO_PAGO_ACCESS_TOKEN || "").trim();
}
const PAGOS_NS = "mercadopago_pagos";
const MONEDA = "ARS";
const MP_TIMEOUT_MS = () => Math.max(1000, Number(process.env.MERCADO_PAGO_TIMEOUT_MS) || 10000);

/** Premium incluye el Modo Adulto: el checkout exige mayoría de edad verificada (fecha de Google). */
export function exigirMayorDeEdad(userId) {
  const ev = verificacionEdad.evaluar(userId);
  if (ev.estado === "mayor") return ev;
  throw new HttpError(403, ev.estado === "menor"
    ? "Premium no disponible: requiere ser mayor de 18 años"
    : "Premium requiere verificar la edad con la cuenta de Google (fecha de nacimiento)");
}

/** data.id de la notificación: el mismo valor se firma (manifest) y se procesa (query primero, como indica MP). */
function idNotificacion({ query = {}, body = {} } = {}) {
  return String(query["data.id"] || body?.data?.id || "");
}

/**
 * Firma del webhook de Mercado Pago (x-signature: "ts=...,v1=..."; manifest "id:<data.id>;request-id:<x-request-id>;ts:<ts>;").
 * Con MERCADO_PAGO_WEBHOOK_SECRET es obligatoria. Sin secreto: rechazado en producción, aceptado en dev.
 */
export function verificarFirmaWebhook({ headers = {}, query = {}, body = {} } = {}) {
  const secret = String(process.env.MERCADO_PAGO_WEBHOOK_SECRET || "").trim();
  if (!secret) {
    if (String(process.env.NODE_ENV || "").toLowerCase() === "production") {
      throw new HttpError(401, "Webhook rechazado: MERCADO_PAGO_WEBHOOK_SECRET no configurado");
    }
    return { verificado: false, motivo: "sin_secreto_dev" };
  }
  const firma = String(headers["x-signature"] || "");
  const requestId = String(headers["x-request-id"] || "");
  const partes = Object.fromEntries(firma.split(",").map(p => p.split("=").map(x => x.trim())).filter(p => p.length === 2));
  const dataId = idNotificacion({ query, body });
  if (!partes.ts || !partes.v1 || !dataId) throw new HttpError(401, "Firma de webhook inválida");
  const idManifest = /^[a-z0-9]+$/i.test(dataId) ? dataId.toLowerCase() : dataId;
  const manifest = `id:${idManifest};${requestId ? `request-id:${requestId};` : ""}ts:${partes.ts};`;
  const esperado = crypto.createHmac("sha256", secret).update(manifest).digest("hex");
  const a = Buffer.from(esperado, "hex");
  const b = Buffer.from(String(partes.v1), "hex");
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new HttpError(401, "Firma de webhook inválida");
  return { verificado: true };
}
const MOCK_NS = "mercadopago_mock";
function baseUrl() {
  return (String(process.env.BACKEND_PUBLIC_URL || "").trim() || `http://localhost:${process.env.PORT || 3000}`).replace(/\/+$/, "");
}

const API_BASE = "https://api.mercadopago.com";

function getAccessToken() {
  const token = String(process.env.MERCADO_PAGO_ACCESS_TOKEN || "").trim();
  if (!token) {
    throw new HttpError(503, "MERCADO_PAGO_ACCESS_TOKEN no está configurado");
  }
  return token;
}

async function mercadoPagoRequest(path, options = {}) {
  const accessToken = getAccessToken();
  const allowed = /^\/(checkout\/preferences|v1\/payments\/[A-Za-z0-9_-]+)$/;

  if (!allowed.test(path)) {
    throw new HttpError(400, "Ruta de Mercado Pago inválida");
  }

  const response = await fetch(`${API_BASE}${path}`, {
    signal: AbortSignal.timeout(MP_TIMEOUT_MS()),
    ...options,
    headers: {
      Authorization: "Bearer " + accessToken,
      "Content-Type": "application/json",
      ...(options.method && options.method !== "GET"
        ? { "X-Idempotency-Key": crypto.randomUUID() }
        : {}),
      ...(options.headers || {})
    }
  });

  const raw = await response.text();
  let data = null;
  try {
    data = raw ? JSON.parse(raw) : null;
  } catch {
    data = raw || null;
  }

  if (!response.ok) {
    throw new HttpError(response.status, data?.message || "Error en Mercado Pago", data);
  }

  return data;
}

// Página puente pública (GitHub Pages): recibe la vuelta de Checkout Pro y abre la app por deep link
// (me2://pago). Es https estable (el túnel cambia), requisito de MP para auto_return.
export const BACK_URL_BASE_DEFAULT = "https://l0b091.github.io/me2/pago.html";

function conEstado(base, estado) {
  return `${base}${base.includes("?") ? "&" : "?"}estado=${estado}`;
}

function construirUrlsRetorno() {
  const success = String(process.env.MERCADO_PAGO_SUCCESS_URL || "").trim();
  const pending = String(process.env.MERCADO_PAGO_PENDING_URL || "").trim();
  const failure = String(process.env.MERCADO_PAGO_FAILURE_URL || "").trim();

  // Las tres explícitas tienen prioridad (compatibilidad con la configuración anterior).
  if (success && pending && failure) {
    return { success, pending, failure };
  }

  // Si no: MP_BACK_URL_BASE (o la página puente por defecto) con ?estado=success|pending|failure.
  // MP_BACK_URL_BASE=off desactiva las back_urls.
  const base = String(process.env.MP_BACK_URL_BASE ?? "").trim() || BACK_URL_BASE_DEFAULT;
  if (/^(off|none|no|false|0)$/i.test(base)) return undefined;
  if (!/^https?:\/\/[^\s]+$/i.test(base)) return undefined;
  return { success: conEstado(base, "success"), pending: conEstado(base, "pending"), failure: conEstado(base, "failure") };
}

/**
 * URLs de la preferencia (Checkout Pro), por entorno:
 * - notification_url = <BACKEND_PUBLIC_URL>/api/mercadopago/webhook (sin BACKEND_PUBLIC_URL no se envía).
 * - back_urls = MERCADO_PAGO_SUCCESS_URL / _PENDING_URL / _FAILURE_URL (las tres), si no MP_BACK_URL_BASE
 *   (default: página puente https://l0b091.github.io/me2/pago.html que vuelve a la app por me2://pago).
 * - auto_return=approved solo con back_urls.success https: MP rechaza auto_return sin back_urls.success válida.
 *   La vuelta a la app nunca acredita nada por sí sola: la app verifica el payment_id con /api/mercadopago/verify.
 */
export function urlsPreferencia() {
  const backUrls = construirUrlsRetorno();
  const publica = String(process.env.BACKEND_PUBLIC_URL || "").trim().replace(/\/+$/, "");
  return {
    back_urls: backUrls,
    auto_return: backUrls && /^https:\/\//i.test(backUrls.success) ? "approved" : undefined,
    // source_news=webhooks: MP manda solo Webhooks (firmados con x-signature), no IPN (topic/id, sin firma → 401 y reintentos).
    notification_url: publica ? `${publica}/api/mercadopago/webhook?source_news=webhooks` : undefined
  };
}

function explicarPremium(feature = "M/A") {
  return premiumManager.explicarPlan(feature);
}

async function generarLinkPago(userId, feature = "M/A") {
  const usuario = usuariosMemoria.obtenerUsuarioPorId(userId);
  if (!usuario) {
    throw new HttpError(404, "Usuario no encontrado para generar checkout");
  }

  exigirMayorDeEdad(userId);
  const precioARS = premiumManager.obtenerPrecioPremium();
  if (modoMock()) {
    const preferenceId = `mock-pref-${crypto.randomBytes(6).toString("hex")}`;
    const url = `${baseUrl()}/api/mercadopago/mock/checkout/${preferenceId}`;
    storage.writeUserData(MOCK_NS, preferenceId, { preferenceId, userId, feature, amountARS: precioARS, estado: "pendiente", creado: new Date().toISOString() });
    premiumManager.registrarCheckout(userId, { preferenceId, initPoint: url, feature, amountARS: precioARS });
    return { preferenceId, url, sandboxUrl: null, amountARS: precioARS, feature, mock: true };
  }
  const body = {
    items: [
      {
        id: `me2-premium-${feature}`,
        title: `ME2 Premium 30 días - ${feature}`,
        quantity: 1,
        currency_id: "ARS",
        unit_price: precioARS
      }
    ],
    external_reference: userId,
    payer: {
      email: usuario.email,
      name: usuario.displayName || usuario.email
    },
    metadata: {
      userId,
      feature,
      plan: "premium_30_dias"
    },
    ...urlsPreferencia()
  };

  const payload = Object.fromEntries(
    Object.entries(body).filter(([, value]) => value !== undefined)
  );

  const preference = await mercadoPagoRequest("/checkout/preferences", {
    method: "POST",
    body: JSON.stringify(payload)
  });

  premiumManager.registrarCheckout(userId, {
    preferenceId: preference.id,
    initPoint: preference.init_point,
    feature,
    amountARS: precioARS
  });

  return {
    preferenceId: preference.id,
    url: preference.init_point,
    sandboxUrl: preference.sandbox_init_point || null,
    amountARS: precioARS,
    feature
  };
}

async function verificarPago(paymentId, expectedUserId = null) {
  if (!paymentId) {
    throw new HttpError(400, "paymentId requerido");
  }

  const payment = modoMock() && String(paymentId).startsWith("mock-pay-")
    ? storage.readUserData(MOCK_NS, String(paymentId), null)
    : await mercadoPagoRequest(`/v1/payments/${paymentId}`);
  if (!payment) throw new HttpError(404, "Pago no encontrado");
  // MP puede devolver la metadata del pago en snake_case (user_id): se aceptan ambas formas.
  const metadataUserId = payment?.metadata?.userId || payment?.metadata?.user_id || null;
  const externalReference = payment?.external_reference || null;
  const duenos = [externalReference, metadataUserId].filter(Boolean).map(String);
  const userId = expectedUserId || metadataUserId || externalReference;

  if (!userId) {
    throw new HttpError(422, "No se pudo determinar el usuario asociado al pago");
  }

  // /verify: el pago tiene que nombrar al usuario autenticado (external_reference o metadata). Sin dueño
  // identificable no se acredita a quien lo reclame.
  if (expectedUserId && !duenos.includes(String(expectedUserId))) {
    throw new HttpError(403, "El pago no pertenece al usuario autenticado");
  }
  // Webhook (sin usuario autenticado): metadata y external_reference no pueden contradecirse y el dueño
  // tiene que ser una cuenta ME2 existente (pagos ajenos de la misma cuenta MP no crean registros).
  if (!expectedUserId) {
    if (new Set(duenos).size > 1) throw new HttpError(422, "El pago tiene datos de usuario inconsistentes");
    if (!usuariosMemoria.obtenerUsuarioPorId(String(userId))) throw new HttpError(422, "El pago no corresponde a un usuario de ME2");
  }

  const previo = storage.readUserData(PAGOS_NS, String(paymentId), null);
  if (previo?.userId) {
    if (previo.userId !== userId) throw new HttpError(409, "El pago ya fue aplicado a otra cuenta");
    // Idempotente: un paymentId ya aplicado no vuelve a extender Premium ni a reactivar el Modo Adulto.
    const estado = premiumManager.obtenerEstado(userId);
    return { ok: true, yaProcesado: true, paymentId, premiumActivo: estado.premiumActivo, premiumHasta: estado.premiumHasta || null, status: "approved" };
  }

  if (payment.status !== "approved") {
    if (String(paymentId).startsWith("mock-pay-")) storage.writeUserData(MOCK_NS, String(paymentId), { ...payment, procesado: true });
    return {
      ok: false,
      paymentId,
      status: payment.status,
      statusDetail: payment.status_detail || null
    };
  }

  const esMock = String(paymentId).startsWith("mock-pay-");
  if (!esMock) {
    const moneda = String(payment.currency_id || "").toUpperCase();
    const monto = Number(payment.transaction_amount);
    const esperado = premiumManager.montoEsperado(userId);
    if (moneda !== MONEDA || !Number.isFinite(monto) || monto + 0.001 < esperado) {
      throw new HttpError(422, "El pago no corresponde al plan Premium (monto o moneda)");
    }
  }
  storage.writeUserData(PAGOS_NS, String(paymentId), { userId, aplicadoEn: new Date().toISOString() });
  const activated = premiumManager.activarPremium(userId, paymentId, {
    aprobadoEn: payment.date_approved || null,
    status: payment.status,
    feature: payment?.metadata?.feature || "M/A",
    preferenceId: payment?.order?.id || null
  });
  let adultBootstrap = null;
  try {
    adultBootstrap = adultMode.habilitarExtension(userId);
  } catch (error) {
    adultBootstrap = { ok: false, error: error?.message || String(error) };
  }
  // Entrega de la palabra clave: queda pendiente para el próximo turno de chat (la redacta el LLM).
  try {
    flujoPremium.alActivarPremium(userId, adultBootstrap?.keyword || null);
  } catch (error) {
    console.error("[mercadoPago] flujoPremium:", error.message);
  }
  return {
    ...activated,
    adultMode: adultBootstrap ? { ok: adultBootstrap.ok, recienAsignada: adultBootstrap.recienAsignada, estado: adultBootstrap.estado } : null
  };
}

// Simula que el usuario pagó la preferencia mock (equivalente al webhook aprobado).
async function pagarMock(preferenceId, estado = "approved", expectedUserId = null) {
  if (!modoMock()) throw new HttpError(403, "Pago simulado deshabilitado: Mercado Pago real configurado");
  const pref = storage.readUserData(MOCK_NS, String(preferenceId || ""), null);
  if (!pref?.userId) throw new HttpError(404, "Preferencia mock no encontrada");
  if (expectedUserId && pref.userId !== expectedUserId) throw new HttpError(403, "La preferencia no pertenece al usuario");
  const paymentId = `mock-pay-${crypto.randomBytes(6).toString("hex")}`;
  storage.writeUserData(MOCK_NS, paymentId, {
    id: paymentId, status: estado, status_detail: estado === "approved" ? "accredited" : "cc_rejected_other_reason",
    external_reference: pref.userId, metadata: { userId: pref.userId, feature: pref.feature }, order: { id: preferenceId }
  });
  storage.writeUserData(MOCK_NS, preferenceId, { ...pref, estado, paymentId });
  // El dueño ya se comprobó contra la preferencia mock: se acredita a ese usuario.
  return verificarPago(paymentId, pref.userId);
}

async function procesarWebhook(body = {}, query = {}, headers = {}) {
  verificarFirmaWebhook({ headers, query, body });
  const topic = body.type || query.type || query.topic || body.topic || "";
  const action = body.action || "";
  // Se procesa exactamente el id que se verificó en la firma (no otro del body).
  const paymentId = idNotificacion({ query, body }) || null;

  if (
    !paymentId ||
    (!String(topic).includes("payment") &&
      !String(action).includes("payment"))
  ) {
    return { ok: true, ignored: true };
  }

  return verificarPago(String(paymentId));
}

export default {
  modoMock,
  urlsPreferencia,
  exigirMayorDeEdad,
  verificarFirmaWebhook,
  pagarMock,
  explicarPremium,
  generarLinkPago,
  verificarPago,
  procesarWebhook
};
