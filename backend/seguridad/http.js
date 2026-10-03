// seguridad/http.js — hardening HTTP: headers estilo helmet, CORS estricto, auth por defecto (whitelist de públicos),
// validación de /chat y redacción de secretos/PII en logs.
import login from "../auth/login.js";

export function esProduccion() {
  return String(process.env.NODE_ENV || "").trim().toLowerCase() === "production";
}

// ---------------------------------------------------------------- headers
export function headersSeguridad(req, res, next) {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  res.setHeader("Cross-Origin-Resource-Policy", "same-site");
  res.setHeader("X-DNS-Prefetch-Control", "off");
  res.setHeader("X-Permitted-Cross-Domain-Policies", "none");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
  // API JSON + medios: nada de scripts, frames ni recursos externos.
  res.setHeader("Content-Security-Policy", "default-src 'none'; img-src 'self'; media-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
  if (esProduccion()) res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  if (!req.path.startsWith("/api/media/")) res.setHeader("Cache-Control", "no-store");
  res.removeHeader("X-Powered-By");
  next();
}

// ---------------------------------------------------------------- CORS
const ORIGENES_DEV = ["http://localhost", "http://127.0.0.1", "http://10.0.2.2"];

/**
 * Orígenes permitidos: CORS_ALLOWED_ORIGINS (coincidencia EXACTA de origen, con puerto si corresponde).
 * Sin configurar: en producción ninguno (la app Android no envía Origin); fuera de producción, localhost con cualquier puerto.
 */
export function origenesPermitidos() {
  const configurados = String(process.env.CORS_ALLOWED_ORIGINS || "").split(",").map(s => s.trim().replace(/\/+$/, "")).filter(Boolean);
  return { exactos: configurados, dev: configurados.length === 0 && !esProduccion() ? ORIGENES_DEV : [] };
}

export function origenPermitido(origin, permitidos = origenesPermitidos()) {
  if (!origin) return true; // clientes nativos / curl: sin Origin no hay CORS
  if (permitidos.exactos.includes(origin)) return true;
  return permitidos.dev.some(base => origin === base || new RegExp(`^${base.replace(/[.]/g, "\\.")}:\\d{1,5}$`).test(origin));
}

/** Rechaza explícitamente (403) los orígenes de navegador no permitidos, antes de cualquier ruta. */
export function corsEstricto(req, res, next) {
  const origin = req.headers.origin;
  if (!origenPermitido(origin)) return res.status(403).json({ ok: false, error: "Origen no permitido" });
  if (origin) {
    res.setHeader("Access-Control-Allow-Origin", origin);
    res.setHeader("Vary", "Origin");
    res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    res.setHeader("Access-Control-Max-Age", "600");
  }
  if (req.method === "OPTIONS") return res.status(204).end();
  return next();
}

// ---------------------------------------------------------------- auth por defecto
/** Rutas públicas (todo lo demás exige Bearer válido). [método, regex]. */
export const RUTAS_PUBLICAS = [
  ["GET", /^\/$/],
  ["GET", /^\/health$/],
  ["POST", /^\/api\/auth\/google$/],
  ["POST", /^\/api\/auth\/(register|login)$/], // devuelven 404 salvo LOCAL_AUTH_ENABLED=true (dev)
  ["GET", /^\/api\/hora$/],
  ["GET", /^\/api\/mercadopago\/plan$/],
  ["POST", /^\/api\/mercadopago\/webhook$/], // autenticado por firma x-signature
  ["GET", /^\/api\/mercadopago\/mock\/checkout\/[A-Za-z0-9_-]{1,80}$/] // 404 fuera de modo mock
];
/** Rutas que validan identidad por su cuenta (anónimo solo con ME2_ALLOW_ANONYMOUS=true, dev). */
export const RUTAS_IDENTIDAD_PROPIA = [["POST", /^\/chat$/], ["POST", /^\/api\/iniciativas\/evaluar$/]];

const coincide = (lista, req) => lista.some(([m, re]) => m === req.method && re.test(req.path));

export function esRutaPublica(req) {
  return coincide(RUTAS_PUBLICAS, req);
}

export function authPorDefecto(req, res, next) {
  if (req.method === "OPTIONS" || esRutaPublica(req) || coincide(RUTAS_IDENTIDAD_PROPIA, req)) return next();
  const header = String(req.headers.authorization || "");
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const auth = token ? login.validarToken(token) : null;
  if (!auth) return res.status(401).json({ ok: false, error: "Autenticación requerida" });
  req.auth = auth;
  req.authToken = token;
  return next();
}

// ---------------------------------------------------------------- validación de /chat
export const LIMITES_CHAT = Object.freeze({ mensaje: 4000, contextoBytes: 256 * 1024, historial: 60, memorias: 300 });
const CAMPOS_CONTEXTO = new Set(["memoriaLocal", "premiumLocal", "lat", "lon", "zonaHoraria", "ciudad", "iniciativa"]);

/** Valida el cuerpo de /chat y devuelve SOLO los campos de contexto admitidos (nunca userId/premium/adulto del cliente). */
export function validarCuerpoChat(body = {}) {
  const { mensaje, contexto } = body || {};
  if (typeof mensaje !== "string" || !mensaje.trim()) return { error: "Mensaje inválido" };
  if (mensaje.length > LIMITES_CHAT.mensaje) return { error: `Mensaje demasiado largo (máx ${LIMITES_CHAT.mensaje})`, status: 413 };
  if (contexto != null && (typeof contexto !== "object" || Array.isArray(contexto))) return { error: "Contexto inválido" };
  const ctx = {};
  for (const [k, v] of Object.entries(contexto || {})) if (CAMPOS_CONTEXTO.has(k)) ctx[k] = v;
  if (Buffer.byteLength(JSON.stringify(ctx)) > LIMITES_CHAT.contextoBytes) return { error: "Contexto demasiado grande", status: 413 };
  for (const k of ["lat", "lon"]) {
    if (ctx[k] == null) continue;
    const n = Number(ctx[k]);
    if (!Number.isFinite(n) || Math.abs(n) > (k === "lat" ? 90 : 180)) delete ctx[k]; else ctx[k] = n;
  }
  if (ctx.zonaHoraria != null && !/^[A-Za-z_]+(\/[A-Za-z0-9_+-]+){0,2}$/.test(String(ctx.zonaHoraria))) delete ctx.zonaHoraria;
  // Ciudad del GPS del teléfono (solo etiqueta para el clima): texto corto de una línea, sin coordenadas no vale.
  if (ctx.ciudad != null) {
    const c = typeof ctx.ciudad === "string" ? ctx.ciudad.replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, 80) : "";
    if (!c || ctx.lat == null || ctx.lon == null) delete ctx.ciudad; else ctx.ciudad = c;
  }
  const m = ctx.memoriaLocal;
  if (m != null) {
    if (typeof m !== "object" || Array.isArray(m)) delete ctx.memoriaLocal;
    else {
      const recortada = { ...m };
      if (Array.isArray(m.recentConversation)) recortada.recentConversation = m.recentConversation.slice(-LIMITES_CHAT.historial);
      for (const k of ["persistentMemories", "importantMemories", "gustos", "disgustos"]) {
        if (Array.isArray(m[k])) recortada[k] = m[k].slice(-LIMITES_CHAT.memorias);
      }
      ctx.memoriaLocal = recortada;
    }
  }
  return { mensaje, contexto: ctx };
}

// ---------------------------------------------------------------- logs sin secretos ni PII
const RE_EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const RE_BEARER = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;
const RE_JWT = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g;
const RE_HEX_LARGO = /\b[a-f0-9]{32,}\b/gi;
const RE_CLAVES = /\b(sk-[A-Za-z0-9_-]{10,}|gsk_[A-Za-z0-9]{10,}|APP_USR-[A-Za-z0-9-]{10,}|TEST-[0-9a-f-]{20,})\b/g;
const VARIABLES_SECRETAS = /(KEY|TOKEN|SECRET|PASSWORD|CLIENT_SECRET)$/i;

export function redactar(valor) {
  let s = typeof valor === "string" ? valor : (() => { try { return valor instanceof Error ? `${valor.name}: ${valor.message}` : JSON.stringify(valor); } catch { return String(valor); } })();
  if (s == null) return s;
  for (const [nombre, secreto] of Object.entries(process.env)) {
    if (VARIABLES_SECRETAS.test(nombre) && secreto && secreto.length >= 8) s = s.split(secreto).join("***");
  }
  return s.replace(RE_BEARER, "Bearer ***").replace(RE_JWT, "***jwt").replace(RE_CLAVES, "***").replace(RE_HEX_LARGO, "***").replace(RE_EMAIL, "***@***");
}

let instalado = false;
/** Envuelve console.log/info/warn/error para redactar secretos y PII en TODO el backend. */
export function instalarRedaccionLogs(consola = console) {
  if (instalado && consola === console) return;
  for (const nivel of ["log", "info", "warn", "error", "debug"]) {
    const original = consola[nivel].bind(consola);
    consola[nivel] = (...args) => original(...args.map(a => (typeof a === "string" || a instanceof Error || (a && typeof a === "object")) ? redactar(a) : a));
  }
  if (consola === console) instalado = true;
}

export default { headersSeguridad, corsEstricto, authPorDefecto, validarCuerpoChat, redactar, instalarRedaccionLogs, esRutaPublica, origenPermitido, RUTAS_PUBLICAS, LIMITES_CHAT };
