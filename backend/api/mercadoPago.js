import crypto from "crypto";
import premiumManager from "../modulos/premium/premiumManager.js";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import HttpError from "../utils/httpError.js";

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
  const body = {
    items: [
      {
        id: `joi-premium-${feature}`,
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

  const payment = await mercadoPagoRequest(`/v1/payments/${paymentId}`);
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
    return {
      ok: false,
      paymentId,
      status: payment.status,
      statusDetail: payment.status_detail || null
    };
  }

  return premiumManager.activarPremium(userId, paymentId, {
    status: payment.status,
    feature: payment?.metadata?.feature || "M/A",
    preferenceId: payment?.order?.id || null
  });
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
  explicarPremium,
  generarLinkPago,
  verificarPago,
  procesarWebhook
};
