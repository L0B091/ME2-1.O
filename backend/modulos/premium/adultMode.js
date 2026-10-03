import verificacionEdad from "../../auth/verificacionEdad.js";
/**
 * Modo Adulto — estado y guardrail (sin textos: el LLM redacta).
 * Gate Premium → palabra clave por usuario (solo hash en disco) → unlock por SESIÓN → intensidad gradual.
 * Sin la palabra clave en la sesión actual, el modo adulto queda apagado.
 * Contenido solo para avatar adulto ficticio (sin menores).
 */
import crypto from "crypto";
import storage from "../../utils/jsonStorage.js";
import premiumManager from "./premiumManager.js";

const NAMESPACE = "adult_mode";

/** @typedef {"locked"|"keyword_pending"|"awaiting_keyword"|"active"} AdultPhase */
/** @typedef {"none"|"soft_flirt"|"suggestive"|"intimate"|"explicit"} IntensityTier */

export const INTENSITY_ORDER = Object.freeze([
  "none",
  "soft_flirt",
  "suggestive",
  "intimate",
  "explicit"
]);

/** Palabras cotidianas en español (AR) — no sexuales. */
const KEYWORD_POOL = Object.freeze([
  "taza", "mate", "lluvia", "ventana", "cuaderno", "lámpara", "camino",
  "plaza", "bufanda", "reloj", "almohada", "cocina", "jardin", "puente",
  "nube", "sierra", "faro", "anillo", "valija", "timbre", "sillon",
  "cuchara", "mantel", "espejo", "botella", "heladera", "balcon", "pasillo"
]);

/**
 * Señales de chat íntimo/explícito (adultos, consensual, ficticio).
 * Explicit sex talk alone NO desbloquea sin keyword.
 */
const INTIMATE_PATTERNS = [
  /\b(te\s+deseo|quiero\s+besarte|besame|bésame|acariciame|acariciame|sexy|sensual|provoca[rm]e|excitad[oa]|caliente|en\s+la\s+cama|desnud[oa]|lencer[ií]a|morbo|picante)\b/i,
  /\b(hacer\s+el\s+amor|cogemos|coger|follar|sexo|oral|masturb|pene|vagina|tetas|culo|orgasmo|correrte|venite|penetr)\b/i,
  /\b(modo\s+adulto|adult\s*mode|contenido\s+adulto|sexting)\b/i
];

const EXPLICIT_PATTERNS = [
  /\b(coger|follar|sexo\s+oral|masturb|pene|vagina|correrte|orgasmo|penetr|anal)\b/i
];

const SOFT_FLIRT_PATTERNS = [
  /\b(hermosa|linda|preciosa|te\s+miro|me\s+gust[aá]s|flirte|coquete|cariño|amorcito)\b/i
];

const SUGGESTIVE_PATTERNS = [
  /\b(beso|besarte|abrazo\s+largo|cerca\s+m[ií]o|susurr|provoca|lencer|sensual)\b/i
];

const CLIP_BY_INTENSITY = Object.freeze({
  none: { categoria: "calida", etiqueta: "ambiente", honestFallback: true },
  soft_flirt: { categoria: "calida", etiqueta: "flirteo_suave", honestFallback: true },
  suggestive: { categoria: "alegre", etiqueta: "sugerente_teaser", honestFallback: true },
  intimate: { categoria: "calida", etiqueta: "intimo_teaser", honestFallback: true },
  explicit: { categoria: "texting", etiqueta: "explicito_teaser_local", honestFallback: true }
});

function defaultState(userId) {
  return {
    userId,
    extensionEnabled: false,
    keyword: null,
    keywordHash: null,
    unlocked: false,
    unlockedAt: null,
    intensity: "none",
    intensityIndex: 0,
    lastEscalationAt: null,
    adultTurnCount: 0,
    checkoutOfferedAt: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
}

function obtenerRegistro(userId) {
  if (!userId) return defaultState("anonimo");
  return storage.readUserData(NAMESPACE, userId, defaultState(userId));
}

function guardarRegistro(userId, data) {
  const payload = {
    ...defaultState(userId),
    ...data,
    userId,
    updatedAt: new Date().toISOString()
  };
  storage.writeUserData(NAMESPACE, userId, payload);
  return payload;
}

function hashKeyword(keyword, salt = "") {
  return crypto.createHash("sha256").update(`${salt}:${normalizarTexto(keyword).replace(/\s+/g, " ")}`, "utf8").digest("hex");
}

// Fácil de recordar: dos palabras cotidianas distintas (p. ej. "faro lluvia").
function generarKeyword() {
  const i = crypto.randomInt(KEYWORD_POOL.length);
  let j = crypto.randomInt(KEYWORD_POOL.length - 1);
  if (j >= i) j++;
  return `${KEYWORD_POOL[i]} ${KEYWORD_POOL[j]}`;
}

function normalizarTexto(texto = "") {
  return String(texto || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

function mensajeCruzaLimiteIntimo(mensaje) {
  const raw = String(mensaje || "");
  return INTIMATE_PATTERNS.some((re) => re.test(raw));
}

function mensajeEsExplicito(mensaje) {
  return EXPLICIT_PATTERNS.some((re) => re.test(String(mensaje || "")));
}

function detectarSenalIntensidad(mensaje) {
  const raw = String(mensaje || "");
  if (EXPLICIT_PATTERNS.some((re) => re.test(raw))) return "explicit";
  if (SUGGESTIVE_PATTERNS.some((re) => re.test(raw)) || INTIMATE_PATTERNS.some((re) => re.test(raw))) {
    if (/\b(cama|desnud|lencer|excit|morbo|sexo)\b/i.test(raw)) return "intimate";
    return "suggestive";
  }
  if (SOFT_FLIRT_PATTERNS.some((re) => re.test(raw))) return "soft_flirt";
  return "none";
}

// Compara contra el hash guardado: prueba cada par (y palabra) consecutivo del mensaje.
function contieneKeywordHash(mensaje, keywordHash, salt = "") {
  if (!keywordHash) return false;
  const t = normalizarTexto(mensaje).replace(/[^a-z0-9ñ ]+/g, " ").split(/\s+/).filter(Boolean);
  for (let i = 0; i < t.length; i++) {
    if (hashKeyword(t[i], salt) === keywordHash) return true;
    if (i + 1 < t.length && hashKeyword(`${t[i]} ${t[i + 1]}`, salt) === keywordHash) return true;
  }
  return false;
}

function contieneKeywordExacta(mensaje, keyword) {
  return Boolean(keyword) && contieneKeywordHash(mensaje, hashKeyword(keyword));
}

function indiceTier(tier) {
  const idx = INTENSITY_ORDER.indexOf(tier);
  return idx < 0 ? 0 : idx;
}

function clampEscalation(currentTier, targetTier, unlockedTurnCount) {
  const cur = indiceTier(currentTier);
  let tgt = indiceTier(targetTier);
  if (tgt <= cur) return INTENSITY_ORDER[cur];
  // Nunca saltar al máximo en el primer unlock: máximo +1 por turno adulto.
  const maxAllowed = Math.min(INTENSITY_ORDER.length - 1, Math.max(cur + 1, unlockedTurnCount <= 1 ? 1 : cur + 1));
  // Explicit (último) solo tras varios turnos adultos activos.
  if (tgt >= INTENSITY_ORDER.length - 1 && unlockedTurnCount < 4) {
    tgt = Math.min(tgt, INTENSITY_ORDER.length - 2);
  }
  return INTENSITY_ORDER[Math.min(tgt, maxAllowed, cur + 1)];
}

const SESION_MS = () => Number(process.env.ME2_ADULT_SESSION_MIN || 120) * 60000;

// La sesión adulta vence por inactividad (o al cerrar sesión): no queda desbloqueado "para siempre".
function sesionVigente(reg, ahora = Date.now()) {
  if (!reg.unlocked) return false;
  const ultima = Date.parse(reg.lastAdultActivityAt || reg.unlockedAt || 0);
  return Number.isFinite(ultima) && ahora - ultima <= SESION_MS();
}

function bloquearSesion(userId) {
  const reg = obtenerRegistro(userId);
  return guardarRegistro(userId, { ...reg, unlocked: false, unlockedAt: null, lastAdultActivityAt: null, intensity: "none", intensityIndex: 0, adultTurnCount: 0 });
}

function obtenerEstadoPublico(userId) {
  const reg = obtenerRegistro(userId);
  const premium = premiumManager.obtenerEstado(userId);
  const unlocked = sesionVigente(reg);
  const phase = !premium.premiumActivo
    ? "locked"
    : !reg.extensionEnabled || !reg.keywordHash
      ? "keyword_pending"
      : !unlocked
        ? "awaiting_keyword"
        : "active";

  return {
    userId,
    premiumActivo: !!premium.premiumActivo,
    extensionEnabled: !!reg.extensionEnabled,
    hasKeyword: !!reg.keywordHash,
    unlocked,
    intensity: reg.intensity || "none",
    intensityIndex: reg.intensityIndex || 0,
    phase,
    clipHint: CLIP_BY_INTENSITY[reg.intensity || "none"] || CLIP_BY_INTENSITY.none
  };
}

/**
 * Habilita extensión Adult Mode tras Premium (genera keyword si falta).
 */
function habilitarExtension(userId) {
  const premium = premiumManager.obtenerEstado(userId);
  if (!premium.premiumActivo) {
    const err = new Error("Premium requerido para Modo Adulto");
    err.status = 403;
    throw err;
  }
  // Mayoría de edad verificada (fecha de nacimiento de Google), independiente de cómo se llegó a Premium.
  if (verificacionEdad.evaluar(userId).estado !== "mayor") {
    const err = new Error("Modo Adulto requiere ser mayor de 18 años (verificación de edad)");
    err.status = 403;
    throw err;
  }
  let reg = obtenerRegistro(userId);
  if (!reg.keywordHash) {
    const keyword = generarKeyword();
    const keywordSalt = crypto.randomBytes(8).toString("hex");
    reg = guardarRegistro(userId, {
      ...reg,
      extensionEnabled: true,
      keyword: null,
      keywordSalt,
      keywordHash: hashKeyword(keyword, keywordSalt),
      unlocked: false,
      intensity: "none",
      intensityIndex: 0,
      adultTurnCount: 0
    });
    // La palabra en claro solo se devuelve acá, una vez, para que el orquestador la entregue.
    return { ok: true, recienAsignada: true, keyword, estado: obtenerEstadoPublico(userId) };
  }
  reg = guardarRegistro(userId, { ...reg, extensionEnabled: true });
  return { ok: true, recienAsignada: false, keyword: null, estado: obtenerEstadoPublico(userId) };
}

/**
 * Estado del modo adulto para este turno (sin respuesta armada; el orquestador pasa hechos al LLM).
 * @returns {{ evento: string, adult: object, video?: object|null, pedidoAdulto: boolean }}
 */
async function procesarEnChat(userId, mensaje, opciones = {}) {
  const premium = opciones.premium || (userId ? premiumManager.obtenerEstado(userId) : { premiumActivo: false });
  const ahora = opciones.ahora ?? Date.now();
  const pedidoAdulto = mensajeCruzaLimiteIntimo(mensaje);
  if (!userId || userId === "anonimo" || !premium.premiumActivo) {
    return { evento: pedidoAdulto ? "premium_requerido" : "sin_cambios", pedidoAdulto, adult: obtenerEstadoPublico(userId || "anonimo"), video: null };
  }
  let reg = obtenerRegistro(userId);
  if (!reg.keywordHash) {
    return { evento: pedidoAdulto ? "keyword_no_asignada" : "sin_cambios", pedidoAdulto, adult: obtenerEstadoPublico(userId), video: null };
  }
  if (reg.unlocked && !sesionVigente(reg, ahora)) reg = bloquearSesion(userId);
  if (!reg.unlocked) {
    if (contieneKeywordHash(mensaje, reg.keywordHash, reg.keywordSalt || "")) {
      const iso = new Date(ahora).toISOString();
      reg = guardarRegistro(userId, { ...reg, unlocked: true, unlockedAt: iso, lastAdultActivityAt: iso, intensity: "soft_flirt", intensityIndex: 1, adultTurnCount: 1, lastEscalationAt: iso });
      return { evento: "desbloqueado_con_keyword", pedidoAdulto, adult: obtenerEstadoPublico(userId), video: { ...CLIP_BY_INTENSITY.soft_flirt, assetName: null, loop: true } };
    }
    return { evento: pedidoAdulto ? "bloqueado_sin_keyword" : "sin_cambios", pedidoAdulto, adult: obtenerEstadoPublico(userId), video: null };
  }
  // Activo en esta sesión: escalar gradual, refrescar actividad.
  const senal = detectarSenalIntensidad(mensaje);
  const next = clampEscalation(reg.intensity || "none", senal === "none" ? reg.intensity : senal, (reg.adultTurnCount || 0) + 1);
  const adultTurnCount = (reg.adultTurnCount || 0) + (senal !== "none" || pedidoAdulto ? 1 : 0);
  reg = guardarRegistro(userId, {
    ...reg, intensity: next, intensityIndex: indiceTier(next), adultTurnCount,
    lastAdultActivityAt: new Date(ahora).toISOString(),
    lastEscalationAt: next !== reg.intensity ? new Date(ahora).toISOString() : reg.lastEscalationAt
  });
  const clip = CLIP_BY_INTENSITY[reg.intensity] || CLIP_BY_INTENSITY.none;
  return { evento: "activo", pedidoAdulto, adult: { ...obtenerEstadoPublico(userId), activeTone: reg.intensity }, video: { ...clip, assetName: null, loop: true } };
}

export default {
  INTENSITY_ORDER,
  CLIP_BY_INTENSITY,
  obtenerEstado: obtenerEstadoPublico,
  obtenerRegistro,
  habilitarExtension,
  generarKeyword,
  mensajeCruzaLimiteIntimo,
  contieneKeywordExacta,
  procesarEnChat,
  bloquearSesion,
  sesionVigente,
  contieneKeywordHash,
  hashKeyword
};
