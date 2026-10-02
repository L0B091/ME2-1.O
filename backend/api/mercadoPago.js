import crypto from "crypto";
import premiumManager from "../modulos/premium/premiumManager.js";
import adultMode from "../modulos/premium/adultMode.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import HttpError from "../utils/httpError.js";
import storage from "../utils/jsonStorage.js";
import flujoPremium from "../modulos/premium/flujoPremium.js";

// Sin MERCADO_PAGO_ACCESS_TOKEN: modo simulado (link y pago mock) para desarrollo/pruebas.
export function modoMock() {
  return !String(process.env.MERCADO_PAGO_ACCESS_TOKEN || "").trim();
}
const MOCK_NS = "mercadopago_mock";
function baseUrl() {
  return (process.env.BACKEND_PUBLIC_URL || `http://localhost:${process.env.PORT || 3000}`).replace(/\/$/, "");
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

function construirUrlsRetorno() {
  const success = process.env.MERCADO_PAGO_SUCCESS_URL;
  const pending = process.env.MERCADO_PAGO_PENDING_URL;
  const failure = process.env.MERCADO_PAGO_FAILURE_URL;

  if (!success || !pending || !failure) {
    return undefined;
  }

  return { success, pending, failure };
}

function explicarPremium(feature = "M/A") {
  return premiumManager.explicarPlan(feature);
}

async function generarLinkPago(userId, feature = "M/A") {
  const usuario = usuariosMemoria.obtenerUsuarioPorId(userId);
  if (!usuario) {
    throw new HttpError(404, "Usuario no encontrado para generar checkout");
  }

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
    auto_return: "approved",
    back_urls: construirUrlsRetorno(),
    notification_url: process.env.BACKEND_PUBLIC_URL
      ? `${process.env.BACKEND_PUBLIC_URL.replace(/\/$/, "")}/api/mercadopago/webhook`
      : undefined
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
  const metadataUserId = payment?.metadata?.userId || null;
  const externalReference = payment?.external_reference || null;
  const userId = expectedUserId || metadataUserId || externalReference;

  if (!userId) {
    throw new HttpError(422, "No se pudo determinar el usuario asociado al pago");
  }

  if (
    expectedUserId &&
    metadataUserId &&
    metadataUserId !== expectedUserId &&
    externalReference !== expectedUserId
  ) {
    throw new HttpError(403, "El pago no pertenece al usuario autenticado");
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

  const activated = premiumManager.activarPremium(userId, paymentId, {
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
  return procesarWebhook({ type: "payment", data: { id: paymentId } });
}

async function procesarWebhook(body = {}, query = {}) {
  const topic = body.type || query.topic || body.topic || "";
  const action = body.action || "";
  const paymentId = body?.data?.id || query["data.id"] || query.id || null;

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
  pagarMock,
  explicarPremium,
  generarLinkPago,
  verificarPago,
  procesarWebhook
};
