// reaccionAudiovisual.js — el orquestador decide TIPO de respuesta audiovisual (categoría + subcategoría + intensidad).
// Nunca elige archivos: la selección concreta (variantes, anti-repetición, fallback) la hace el sistema audiovisual
// del cliente (Android: MediaSelector sobre assets/ME2_MEDIA). Determinista, sin llamadas extra al LLM.

export const REACCIONES_V1 = [
  "ALEGRIA", "RISAS", "SORPRESA", "CURIOSIDAD", "PENSATIVA", "DUDA", "CONFUSION", "TRISTEZA", "EMPATIA",
  "PREOCUPACION", "ENOJO", "MOLESTIA", "VERGUENZA", "TIMIDEZ", "COQUETA", "AFECTO", "ORGULLO", "ALIVIO",
  "CANSANCIO", "SARCASMO",
  // Galería v4: emociones nuevas (con NORMAL/MEDIO/MAXIMO en 02_REACCIONES).
  "TERNURA", "ENTUSIASMO", "NOSTALGIA", "PICARDIA", "ASOMBRO", "GRATITUD"
];
// Clips presentes en ME2_MEDIA (galería v4). Variantes de conversación y loops neutrales disponibles.
export const VARIANTES = Object.freeze({
  ESPERANDO: ["ESPERANDO_001", "ESPERANDO_002", "ESPERANDO_003"],
  PENSANDO: ["PENSANDO_001", "PENSANDO_002", "PENSANDO_003"],
  NEUTRAL: ["NEUTRAL_001", "NEUTRAL_002", "NEUTRAL_004", "NEUTRAL_005", "NEUTRAL_006"]
});
const SALUDO_RE = /^\s*(hola+|holi+s?|buen(os|as)? (dias|tardes|noches)|buen dia|hey|ey|que tal|como estas|como andas|saludos)\b/;
const DESPEDIDA_RE = /\b(chau+|chao|adios|hasta (luego|manana|pronto|la vista)|nos vemos|me voy|buenas noches|me voy a dormir|bye)\b/;
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
  AFECTO: [/\b(te quiero|te amo|te extrane|abrazo|beso|sos lo mejor|te adoro)\b/, /❤️|♥|🥰|🤗/],
  GRATITUD: [/\b(gracias|muchas gracias|te agradezco|mil gracias|agradecid[oa])\b/, /🙏/],
  TERNURA: [/\b(tiern[oa]|cachorr[oa]|bebe|gatito|perrito|que dulce|awww*)\b/, /🥹|🐶|🐱/],
  ENTUSIASMO: [/\b(vamos|me muero de ganas|no veo la hora|que emocion|emocionad[oa]|a full|tengo ganas)\b/, /🔥|🤩|💪/],
  NOSTALGIA: [/\b(nostalgi|me acuerdo|cuando era chic[oa]|extrano (esa|esos|aquel)|en mi infancia|antes era|recuerdos)\b/],
  PICARDIA: [/\b(travies[oa]|picar[oa]|secreto|no le digas a nadie|adivina que hice|pillin[a]?)\b/, /😜|😈/],
  ASOMBRO: [/\b(impresionante|alucinante|que locura|flashe|asombros[oa]|maravillos[oa])\b/, /🤩|✨/],
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
/** Variante de tipeo según el largo de la respuesta de ME2 (corta → rápido; larga → lento). */
export function escribiendoPara(respuesta = "") {
  const n = String(respuesta || "").trim().length;
  if (n <= 80) return "ESCRIBIENDO_RAPIDO";
  if (n <= 240) return "ESCRIBIENDO_MEDIO";
  return "ESCRIBIENDO_LENTO";
}

function azar(lista, random = Math.random) {
  return lista[Math.floor(random() * lista.length) % lista.length];
}

/** Pistas de conversación del turno: tipeo por largo + variante al azar de ESPERANDO/PENSANDO/neutral. */
export function conversacion({ respuesta = "", random = Math.random } = {}) {
  return {
    escribiendo: escribiendoPara(respuesta),
    esperando: azar(VARIANTES.ESPERANDO, random),
    pensando: azar(VARIANTES.PENSANDO, random),
    neutral: azar(VARIANTES.NEUTRAL, random)
  };
}

/** SALUDO al saludar, DESPEDIDA al despedirse (null si no aplica). */
export function saludoDe(mensaje = "") {
  const t = normalizar(mensaje).trim();
  if (DESPEDIDA_RE.test(t)) return "DESPEDIDA";
  if (SALUDO_RE.test(t) && t.length <= 60) return "SALUDO";
  return null;
}

export function decidir({ mensaje = "", respuesta = "" } = {}) {
  const t = normalizar(mensaje);
  const saludo = saludoDe(mensaje);
  if (saludo) return { categoria: "SALUDOS", subcategoria: saludo, intensidad: null, motivo: saludo.toLowerCase() };
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

export default { decidir, intensidadDe, conversacion, escribiendoPara, saludoDe, REACCIONES_V1, INTENSIDADES_V1, VARIANTES };
