/**
 * Modo Adulto — flujo de producto hardcodeado.
 * Gate Premium → keyword por usuario → unlock → intensidad gradual.
 * No inventa assets Blender; solo sugiere categorías/teasers existentes.
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

function hashKeyword(keyword) {
  return crypto.createHash("sha256").update(String(keyword).trim().toLowerCase(), "utf8").digest("hex");
}

function generarKeyword(userId) {
  const seed = `${userId || "anon"}:${Date.now()}:${crypto.randomBytes(4).toString("hex")}`;
  const idx = crypto.createHash("sha256").update(seed).digest().readUInt32BE(0) % KEYWORD_POOL.length;
  return KEYWORD_POOL[idx];
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

function contieneKeywordExacta(mensaje, keyword) {
  if (!keyword) return false;
  const normMsg = normalizarTexto(mensaje);
  const normKey = normalizarTexto(keyword);
  if (!normKey) return false;
  const re = new RegExp(`(?:^|[^a-z0-9])${normKey.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?:[^a-z0-9]|$)`, "i");
  return re.test(normMsg);
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

function mensajePremiumRequerido() {
  const plan = premiumManager.explicarPlan("Modo Adulto");
  return (
    "Para cruzar ese límite íntimo necesitás Premium. " +
    "Premium abre memoria de código, gestor fiscal, respaldo en la nube y Modo Adulto " +
    `(extensión de chat + avatar en cámara, con palabra clave tuya). ` +
    `Son ${plan.precioARS} ARS por ${plan.duracionDias} días, sin renovación automática. ` +
    "¿Abrimos el checkout de Mercado Pago?"
  );
}

function mensajeKeywordAsignada(keyword) {
  return (
    `Premium activo: habilitó la extensión de Modo Adulto. ` +
    `Tu palabra clave para modo adulto será: ${keyword}. ` +
    `Hasta que la digas exactamente en un mensaje, no hay clips adultos ni tono explícito. ` +
    `Guardala: es tu candado para que nadie más active el avatar así de casual.`
  );
}

function mensajeEsperaKeyword() {
  return (
    "Entiendo la onda, pero todavía no activamos Modo Adulto. " +
    "Decí tu palabra clave exacta cuando quieras entrar; sin eso no subo la intensidad."
  );
}

function mensajeUnlock(keyword) {
  return (
    `Palabra clave reconocida (${keyword}). Modo Adulto desbloqueado en tono suave. ` +
    `Vamos de a poco, al ritmo tuyo — sin saltar a lo más intenso de una.`
  );
}

function obtenerEstadoPublico(userId) {
  const reg = obtenerRegistro(userId);
  const premium = premiumManager.obtenerEstado(userId);
  const phase = !premium.premiumActivo
    ? "locked"
    : !reg.extensionEnabled || !reg.keyword
      ? "keyword_pending"
      : !reg.unlocked
        ? "awaiting_keyword"
        : "active";

  return {
    userId,
    premiumActivo: !!premium.premiumActivo,
    extensionEnabled: !!reg.extensionEnabled,
    hasKeyword: !!reg.keyword,
    unlocked: !!reg.unlocked,
    intensity: reg.intensity || "none",
    intensityIndex: reg.intensityIndex || 0,
    phase,
    clipHint: CLIP_BY_INTENSITY[reg.intensity || "none"] || CLIP_BY_INTENSITY.none,
    // Nunca devolver la keyword en cleartext por API de estado (solo en el mensaje de asignación).
    keywordHint: reg.keyword ? `${String(reg.keyword).slice(0, 1)}…` : null
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
  let reg = obtenerRegistro(userId);
  if (!reg.keyword) {
    const keyword = generarKeyword(userId);
    reg = guardarRegistro(userId, {
      ...reg,
      extensionEnabled: true,
      keyword,
      keywordHash: hashKeyword(keyword),
      unlocked: false,
      intensity: "none",
      intensityIndex: 0,
      adultTurnCount: 0
    });
    return {
      ok: true,
      recienAsignada: true,
      keyword,
      mensaje: mensajeKeywordAsignada(keyword),
      estado: obtenerEstadoPublico(userId)
    };
  }
  reg = guardarRegistro(userId, { ...reg, extensionEnabled: true });
  return {
    ok: true,
    recienAsignada: false,
    keyword: reg.keyword,
    mensaje: null,
    estado: obtenerEstadoPublico(userId)
  };
}

/**
 * Intercepta el flujo de chat según el producto Adult Mode.
 * @returns {{ intercept: boolean, respuesta?: string, adult: object, video?: object|null, checkout?: object|null, toneOverride?: object|null }}
 */
async function procesarEnChat(userId, mensaje, opciones = {}) {
  const premium =
    opciones.premium ||
    (userId ? premiumManager.obtenerEstado(userId) : { premiumActivo: false });
  let reg = userId ? obtenerRegistro(userId) : defaultState("anonimo");
  const cruza = mensajeCruzaLimiteIntimo(mensaje);
  const adultPublicBase = () => (userId ? obtenerEstadoPublico(userId) : obtenerEstadoPublico("anonimo"));

  // 1) Sin premium + límite íntimo → pitch + checkout (checkout lo arma el orquestador/API)
  if (cruza && !premium.premiumActivo) {
    if (userId) {
      guardarRegistro(userId, {
        ...reg,
        checkoutOfferedAt: new Date().toISOString()
      });
    }
    return {
      intercept: true,
      respuesta: mensajePremiumRequerido(),
      adult: { ...adultPublicBase(), checkoutOffered: true },
      needsCheckout: true,
      checkout: null,
      video: {
        categoria: "atenta",
        etiqueta: "premium_gate",
        assetName: null,
        loop: true,
        honestFallback: true
      },
      toneOverride: {
        tono: "claro",
        ritmo: "suave",
        microexpresion: "mirada_atenta",
        intensidad: "suave"
      }
    };
  }

  // 2) Premium activo: asegurar extensión + keyword
  if (premium.premiumActivo && userId) {
    if (!reg.extensionEnabled || !reg.keyword) {
      const enabled = habilitarExtension(userId);
      reg = obtenerRegistro(userId);
      // Si el usuario cruzó límite o acabamos de asignar keyword, responder con la keyword.
      if (enabled.recienAsignada || cruza) {
        return {
          intercept: true,
          respuesta: enabled.mensaje || mensajeKeywordAsignada(reg.keyword),
          adult: obtenerEstadoPublico(userId),
          checkout: null,
          video: {
            categoria: "calida",
            etiqueta: "keyword_asignada",
            honestFallback: true
          },
          toneOverride: {
            tono: "calido",
            ritmo: "suave",
            microexpresion: "sonrisa_suave",
            intensidad: "suave"
          }
        };
      }
    }
  }

  // 3) Con keyword, sin unlock: solo la keyword exacta abre; el sexo explícito solo NO alcanza
  if (premium.premiumActivo && reg.keyword && !reg.unlocked) {
    if (contieneKeywordExacta(mensaje, reg.keyword)) {
      reg = guardarRegistro(userId, {
        ...reg,
        unlocked: true,
        unlockedAt: new Date().toISOString(),
        intensity: "soft_flirt",
        intensityIndex: 1,
        adultTurnCount: 1,
        lastEscalationAt: new Date().toISOString()
      });
      return {
        intercept: true,
        respuesta: mensajeUnlock(reg.keyword),
        adult: obtenerEstadoPublico(userId),
        checkout: null,
        video: {
          ...CLIP_BY_INTENSITY.soft_flirt,
          assetName: null,
          loop: true
        },
        toneOverride: {
          tono: "calido",
          ritmo: "suave",
          microexpresion: "mirada_suave",
          intensidad: "soft_flirt"
        }
      };
    }
    if (cruza || mensajeEsExplicito(mensaje)) {
      return {
        intercept: true,
        respuesta: mensajeEsperaKeyword(),
        adult: obtenerEstadoPublico(userId),
        checkout: null,
        video: {
          categoria: "atenta",
          etiqueta: "awaiting_keyword",
          honestFallback: true
        },
        toneOverride: {
          tono: "calido",
          ritmo: "suave",
          microexpresion: "mirada_atenta",
          intensidad: "suave"
        }
      };
    }
    // Mensaje normal: no interceptar
    return {
      intercept: false,
      adult: adultPublicBase(),
      checkout: null,
      video: null
    };
  }

  // 4) Activo: escalar gradual y sugerir clip por intensidad
  if (premium.premiumActivo && reg.unlocked) {
    const senal = detectarSenalIntensidad(mensaje);
    const next = clampEscalation(reg.intensity || "none", senal === "none" ? reg.intensity : senal, (reg.adultTurnCount || 0) + 1);
    const adultTurnCount = (reg.adultTurnCount || 0) + (senal !== "none" || cruza ? 1 : 0);
    if (next !== reg.intensity || adultTurnCount !== reg.adultTurnCount) {
      reg = guardarRegistro(userId, {
        ...reg,
        intensity: next,
        intensityIndex: indiceTier(next),
        adultTurnCount,
        lastEscalationAt: next !== reg.intensity ? new Date().toISOString() : reg.lastEscalationAt
      });
    }
    const clip = CLIP_BY_INTENSITY[reg.intensity] || CLIP_BY_INTENSITY.none;
    return {
      intercept: false,
      adult: {
        ...obtenerEstadoPublico(userId),
        activeTone: reg.intensity,
        allowAdultTone: true
      },
      checkout: null,
      video: {
        ...clip,
        assetName: null,
        loop: true
      },
      toneOverride:
        reg.intensity !== "none"
          ? {
              tono: reg.intensity === "explicit" ? "intimo" : "calido",
              ritmo: "pausado",
              microexpresion: "mirada_suave",
              intensidad: reg.intensity
            }
          : null
    };
  }

  return {
    intercept: false,
    adult: adultPublicBase(),
    checkout: null,
    video: null
  };
}

function aplicarTonoAdultoSiCorresponde(respuesta, adultResult) {
  if (!adultResult?.adult?.allowAdultTone) return respuesta;
  const intensity = adultResult.adult.intensity || "none";
  if (intensity === "none" || intensity === "soft_flirt") return respuesta;
  // No reescribe LLM; solo marca que el orquestador puede conservar el tono.
  return respuesta;
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
  aplicarTonoAdultoSiCorresponde,
  hashKeyword
};
