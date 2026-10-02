// flujoPremium.js — máquina de estados del alta Premium (DATOS; el LLM redacta todo).
// intento bloqueado → oferta → (no: se respeta) | (sí: verificación de edad Google + confirmación 18+)
// → link de Mercado Pago → pago verificado → Premium activo + palabra clave a entregar una vez.
import storage from "../../utils/jsonStorage.js";
import premiumManager, { PLAN } from "./premiumManager.js";
import adultMode from "./adultMode.js";
import verificacionEdad from "../../auth/verificacionEdad.js";

const NAMESPACE = "flujo_premium";
const VIGENCIA_OFERTA_MS = 30 * 60000;

const FUNCIONES = [
  [/\b(modo adulto|contenido adulto|modo \+18)\b/i, "Modo Adulto"],
  [/\b(gestor fiscal|comprobantes?|factura(s|ción)?|monotributo|contador)\b/i, "Gestor Fiscal"],
  [/\b(gestor de c[oó]digo|memoria de c[oó]digo|guard(a|á|ar) (este |mi )?c[oó]digo|modo desarrollador)\b/i, "Gestor de código"],
  [/\b(respaldo (en la )?nube|backup|copia de seguridad|restaurar (mi )?memoria)\b/i, "Respaldo en la nube"]
];
const INTENCION = /\b(quiero|quisiera|activ|us(a|á|ar)|abr(i|í|ir)|habilit|pas(a|á|ame)|pon(e|é)|hac(e|é)|guard|necesito|prend|entr(ar|emos)|dame)\w*/i;
const SI = /^\s*(s[ií]+|dale|ok(ey)?|bueno|de una|obvio|me suscribo|suscrib\w*|vamos|claro|acepto)(?=[\s,.!¡]|$)/i;
const NO = /^\s*(no+\b|nah|paso|ahora no|despu[eé]s|otro d[ií]a|m[aá]s adelante|no gracias|no quiero)/i;
const CONFIRMA_18 = /(soy mayor( de edad| de 18)?|tengo (1[89]|[2-9]\d) a[nñ]os|\bconfirmo\b|\+ ?18|18 ?\+|mayor de 18)/i;

function base(userId) {
  return { userId, estado: null, feature: null, desde: null, link: null, keywordPendiente: null, historial: [] };
}
export function obtener(userId) {
  return { ...base(userId), ...storage.readUserData(NAMESPACE, userId, base(userId)) };
}
function guardar(userId, estado, cambios = {}, ahora = Date.now()) {
  const actual = obtener(userId);
  const nuevo = { ...actual, ...cambios, estado, desde: ahora, historial: [...actual.historial, { estado, timestamp: ahora }].slice(-30) };
  storage.writeUserData(NAMESPACE, userId, nuevo);
  return nuevo;
}

// Función Premium que el usuario intenta usar (o null). Un pedido íntimo/erótico cuenta como Modo Adulto.
export function detectarIntento(mensaje = "") {
  if (adultMode.mensajeCruzaLimiteIntimo(mensaje)) return "Modo Adulto";
  if (!INTENCION.test(mensaje)) return null;
  for (const [re, f] of FUNCIONES) if (re.test(mensaje)) return f;
  return null;
}

function lineasOferta(feature) {
  const plan = premiumManager.explicarPlan(feature);
  return [
    `Premium: el usuario intentó usar "${feature}", que es función Premium; está BLOQUEADA (plan Free).`,
    `Alcance de Premium: ${PLAN.premium.slice(1).join("; ")}.`,
    `Precio: ARS ${plan.precioARS ?? premiumManager.obtenerPrecioPremium()} por ${plan.duracionDias ?? 30} días, sin renovación automática, pago por Mercado Pago. La app suma mejoras todos los meses.`,
    "Paso pendiente del flujo Premium: preguntarle si quiere suscribirse."
  ];
}

function lineasEdad(ev) {
  if (ev.estado === "sin_dato") return [
    "Premium: el usuario aceptó suscribirse. Verificación de edad: la cuenta de Google NO tiene fecha de nacimiento disponible.",
    "Premium sigue bloqueado hasta que cargue su fecha de nacimiento en su cuenta de Google y vuelva a iniciar sesión con Google (no se genera link de pago)."
  ];
  if (ev.estado === "menor") return [
    "Premium: verificación de edad FALLIDA — la fecha de nacimiento de la cuenta de Google indica menos de 18 años.",
    "Premium y Modo Adulto no disponibles para este usuario; no se genera link de pago."
  ];
  return [
    "Premium: verificación de edad por cuenta de Google OK (18 o más).",
    "Paso pendiente del flujo Premium: que el usuario confirme explícitamente que es mayor de 18 años antes de generar el link de pago."
  ];
}

/**
 * Procesa el turno. opciones.generarLink(userId, feature) → { url, preferenceId, mock }.
 * @returns {{ lineas: string[], estado: string|null, link: object|null, evento: string|null }}
 */
export async function procesar(userId, mensaje, { premium, generarLink, ahora = Date.now() } = {}) {
  if (!userId || userId === "anonimo") return { lineas: [], estado: null, link: null, evento: null };
  let f = obtener(userId);
  const premiumActivo = Boolean(premium?.premiumActivo ?? premiumManager.obtenerEstado(userId).premiumActivo);

  if (premiumActivo) {
    if (f.keywordPendiente) {
      const kw = f.keywordPendiente;
      guardar(userId, "activado", { keywordPendiente: null, keywordEntregada: ahora }, ahora);
      return {
        estado: "activado", link: null, evento: "keyword_entregada",
        lineas: [
          "Premium: pago verificado; Premium ACTIVO con acceso completo (todas las funciones Premium).",
          `Palabra clave del modo adulto asignada al usuario (comunicársela ahora; se muestra una única vez): ${kw}`,
          "Regla de privacidad del modo adulto: solo se habilita cuando el usuario escribe esa palabra clave; sin ella no hay contenido erótico; vale solo para la sesión actual."
        ]
      };
    }
    return { lineas: [], estado: f.estado, link: null, evento: null };
  }

  const intento = detectarIntento(mensaje);
  const vigente = f.estado && ahora - (f.desde || 0) < VIGENCIA_OFERTA_MS;

  if (f.estado === "edad_rechazada") {
    return intento ? { estado: f.estado, link: null, evento: "bloqueado_edad", lineas: lineasEdad({ estado: "menor" }) } : { lineas: [], estado: f.estado, link: null, evento: null };
  }
  if (vigente && f.estado === "ofrecido") {
    if (NO.test(mensaje)) {
      guardar(userId, null, { feature: null, rechazadoEn: ahora }, ahora);
      return { estado: null, link: null, evento: "rechazado", lineas: [`Premium: el usuario respondió que NO quiere suscribirse ahora ("${String(mensaje).slice(0, 80)}"). Premium no activado; no insistir.`] };
    }
    if (SI.test(mensaje)) return verificarEdad(userId, f, ahora);
  }
  if (vigente && f.estado === "edad_pendiente" && (SI.test(mensaje) || intento || /\b(listo|ya (la )?cargu[eé]|ya est[aá])\b/i.test(mensaje))) {
    return verificarEdad(userId, f, ahora);
  }
  if (vigente && f.estado === "confirmar_18") {
    if (CONFIRMA_18.test(mensaje)) {
      let link = null;
      try { link = await generarLink(userId, f.feature || "Premium"); } catch (error) { link = { error: error.message }; }
      if (!link?.url) {
        guardar(userId, "confirmar_18", {}, ahora);
        return { estado: "confirmar_18", link: null, evento: "link_no_disponible", lineas: ["Premium: no se pudo generar el link de Mercado Pago en este momento (servicio no disponible)."] };
      }
      guardar(userId, "pago_pendiente", { link: { url: link.url, preferenceId: link.preferenceId, mock: Boolean(link.mock) }, mayorConfirmado: ahora }, ahora);
      return {
        estado: "pago_pendiente", link, evento: "link_generado",
        lineas: [
          "Premium: el usuario confirmó ser mayor de 18 años.",
          `Link de pago de Mercado Pago para Premium${link.mock ? " (modo simulado de prueba)" : ""}: ${link.url}`,
          "Premium se activa automáticamente cuando Mercado Pago confirma el pago."
        ]
      };
    }
    if (NO.test(mensaje)) {
      guardar(userId, null, { feature: null, rechazadoEn: ahora }, ahora);
      return { estado: null, link: null, evento: "rechazado", lineas: ["Premium: el usuario no confirmó ser mayor de 18; no se genera link de pago. No insistir."] };
    }
  }
  if (f.estado === "pago_pendiente" && f.link?.url) {
    return { estado: f.estado, link: f.link, evento: intento ? "pago_pendiente" : null, lineas: [`Premium: pago pendiente de confirmación; link de Mercado Pago ya enviado: ${f.link.url}`] };
  }
  if (intento) {
    guardar(userId, "ofrecido", { feature: intento }, ahora);
    return { estado: "ofrecido", link: null, evento: "oferta", lineas: lineasOferta(intento) };
  }
  return { lineas: [], estado: f.estado, link: null, evento: null };
}

function verificarEdad(userId, f, ahora) {
  const ev = verificacionEdad.evaluar(userId, ahora);
  const estado = ev.estado === "sin_dato" ? "edad_pendiente" : ev.estado === "menor" ? "edad_rechazada" : "confirmar_18";
  guardar(userId, estado, { feature: f.feature }, ahora);
  return { estado, link: null, evento: `edad_${ev.estado}`, lineas: lineasEdad(ev) };
}

// Llamado al verificar el pago: guarda la palabra clave para entregarla en el próximo turno.
export function alActivarPremium(userId, keyword) {
  return guardar(userId, "activado", { keywordPendiente: keyword || obtener(userId).keywordPendiente || null, link: null });
}

export default { obtener, detectarIntento, procesar, alActivarPremium };
