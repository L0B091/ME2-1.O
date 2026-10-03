// reaccionAudiovisual.js — el orquestador decide TIPO de respuesta audiovisual (categoría + subcategoría + intensidad).
// Nunca elige archivos: la selección concreta (variantes, anti-repetición, fallback) la hace el sistema audiovisual
// del cliente (Android: MediaSelector sobre assets/ME2_MEDIA). Determinista, sin llamadas extra al LLM.

export const REACCIONES_V1 = [
  "ALEGRIA", "RISAS", "SORPRESA", "CURIOSIDAD", "PENSATIVA", "DUDA", "CONFUSION", "TRISTEZA", "EMPATIA",
  "PREOCUPACION", "ENOJO", "MOLESTIA", "VERGUENZA", "TIMIDEZ", "COQUETA", "AFECTO", "ORGULLO", "ALIVIO",
  "CANSANCIO", "SARCASMO"
];
export const INTENSIDADES_V1 = ["NORMAL", "MEDIO", "MAXIMO"];

// Señales en el mensaje del usuario → reacción del avatar (p. ej. usuario triste → EMPATIA del avatar).
const SENALES = {
  RISAS: [/\b(ja){2,}j?\b/, /\b(je){2,}\b/, /\bjsjs/, /\blol\b/, /\bxd+\b/, /😂|🤣/, /\bchiste\b/, /\bgracios[oa]\b/],
  ALEGRIA: [/\b(genial|buenisimo|excelente|feliz|contento|contenta|que bueno|joya|barbaro|hermoso dia)\b/, /😄|😁|🎉|🥳/],
  ORGULLO: [/\b(aprobe|lo logre|lo conseguí|lo consegui|me recibi|gane|ascendieron|me ascendieron|consegui (el )?trabajo)\b/],
  SORPRESA: [/\b(wow|guau|increible|no puedo creer|en serio\?|no me digas|posta\?)\b/, /😮|😱|🤯/],
  CURIOSIDAD: [/\b(sabias que|te cuento|adivina|que opinas|que pensas|como es)\b/],
  PENSATIVA: [/\b(estuve pensando|me pregunto|reflexion|filosof|sentido de la vida)\b/],
  DUDA: [/\b(no se si|quizas|tal vez|capaz que|no estoy segur[oa])\b/, /🤔/],
  CONFUSION: [/\b(no entiendo|no entendi|que\?+|como que|me perdi|eh\?)\b/, /😕|🤨/],
  EMPATIA: [/\b(triste|deprimid[oa]|llore|llorando|me siento mal|solo|sola|extrano|murio|fallecio|perdi)\b/, /😢|😭|💔/],
  PREOCUPACION: [/\b(preocupad[oa]|miedo|ansiedad|ansios[oa]|nervios[oa]|me duele|enferm[oa]|hospital|urgencia)\b/],
  MOLESTIA: [/\b(callate|molesta|molesto|pesada|aburrida|no me importa|dejame)\b/, /🙄/],
  ENOJO: [/\b(idiota|estupida|inutil|odio|te odio|basura|mierda)\b/, /😡|🤬/],
  VERGUENZA: [/\b(que verguenza|me da verguenza|ups|perdon|me equivoque)\b/, /😳/],
  TIMIDEZ: [/\b(sos linda|sos hermosa|me gustas|que bonita|que linda)\b/, /☺️|🥺/],
  COQUETA: [/\b(sexy|bombon|preciosa|guapa|coqueta|seduc|cita conmigo)\b/, /😏|😘|😉/],
  AFECTO: [/\b(te quiero|te amo|te extrane|abrazo|beso|gracias|sos lo mejor|te adoro)\b/, /❤️|♥|🥰|🤗/],
  ALIVIO: [/\b(por fin|al fin|menos mal|zafe|ya paso|que alivio)\b/, /😌/],
  CANSANCIO: [/\b(cansad[oa]|agotad[oa]|sueno|no doy mas|reventad[oa]|dormir)\b/, /😴|🥱/],
  SARCASMO: [/\b(si claro|obvio que no|aja|re que si|seguro que si)\b/, /🙃/]
};
const INTENSIFICADORES = /\b(muy|re|super|demasiado|mucho|recontra|tan|tanto|terrible|mal)\b/g;

export function normalizar(t = "") {
  return String(t).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
}

function puntaje(texto, patrones) {
  return patrones.reduce((n, re) => n + (re.test(texto) ? 1 : 0), 0);
}

/** NORMAL / MEDIO / MAXIMO según énfasis del mensaje (sin escala numérica 1-5 expuesta). */
export function intensidadDe(original = "", coincidencias = 1) {
  const t = normalizar(original);
  let p = Math.max(0, coincidencias - 1);
  p += Math.min(2, (t.match(INTENSIFICADORES) || []).length);
  const excl = (original.match(/!/g) || []).length;
  p += Math.min(3, excl);
  const letras = original.replace(/[^A-Za-zÁÉÍÓÚÑáéíóúñ]/g, "");
  if (letras.length >= 6 && letras === letras.toUpperCase()) p += 2;
  if (/(.)\1{3,}/.test(t) || /(ja){5,}/.test(t)) p += 1;
  if (p >= 4) return "MAXIMO";
  if (p >= 2) return "MEDIO";
  return "NORMAL";
}

/**
 * @returns {{categoria: "REACCION", subcategoria: string, intensidad: "NORMAL"|"MEDIO"|"MAXIMO", motivo: string}}
 */
export function decidir({ mensaje = "", respuesta = "" } = {}) {
  const t = normalizar(mensaje);
  let mejor = null;
  for (const [sub, patrones] of Object.entries(SENALES)) {
    const n = puntaje(t, patrones);
    if (n > 0 && (!mejor || n > mejor.n)) mejor = { sub, n };
  }
  if (!mejor) {
    // Sin señal en el usuario: mirar la respuesta de ME2 (solo para tono suave), luego pregunta → curiosidad.
    const r = normalizar(respuesta);
    for (const sub of ["RISAS", "SORPRESA", "EMPATIA", "AFECTO"]) {
      if (puntaje(r, SENALES[sub]) > 0) return { categoria: "REACCION", subcategoria: sub, intensidad: "NORMAL", motivo: "respuesta" };
    }
    if (/\?\s*$/.test(mensaje.trim())) return { categoria: "REACCION", subcategoria: "CURIOSIDAD", intensidad: "NORMAL", motivo: "pregunta" };
    return { categoria: "REACCION", subcategoria: "AFECTO", intensidad: "NORMAL", motivo: "default" };
  }
  return { categoria: "REACCION", subcategoria: mejor.sub, intensidad: intensidadDe(mensaje, mejor.n), motivo: "mensaje" };
}

export default { decidir, intensidadDe, REACCIONES_V1, INTENSIDADES_V1 };
