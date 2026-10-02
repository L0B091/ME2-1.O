// estadoEmocional.js — estado emocional del USUARIO como dato de memoria.
// Se detecta de señales explícitas en sus mensajes (palabras clave, con negación simple),
// se persiste por usuario con historial y se pasa al LLM como hecho de contexto.
// No modifica respuestas ni define cómo debe reaccionar el personaje.
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "estado_emocional";
const MAX_HISTORIAL = 30;
const VIGENCIA_MS = 12 * 3600e3;

const SENALES = {
  // Sueño primero: "dormí muy bien" no debe caer en "muy bien" (alegría).
  descansado: ["dormí bien", "dormi bien", "dormí muy bien", "dormi muy bien", "dormí genial", "dormi genial", "dormí bárbaro", "dormi barbaro", "descansé bien", "descanse bien", "dormí como un tronco", "dormi como un tronco", "descansado", "descansada"],
  cansancio_sueno: ["dormí mal", "dormi mal", "dormí poco", "dormi poco", "dormí horrible", "dormi horrible", "dormí fatal", "dormi fatal", "dormí re mal", "dormi re mal", "dormí muy mal", "dormi muy mal", "no pude dormir", "no dormí", "no dormi", "insomnio", "me desvelé", "me desvele", "dormí para el orto", "dormi para el orto"],
  alegria: ["feliz", "contento", "contenta", "alegre", "genial", "re bien", "muy bien", "excelente", "emocionado", "emocionada"],
  tristeza: ["triste", "deprimido", "deprimida", "me siento mal", "estoy mal", "bajoneado", "bajoneada", "angustiado", "angustiada"],
  enojo: ["enojado", "enojada", "molesto", "molesta", "fastidiado", "fastidiada", "harto", "harta", "furioso", "furiosa", "re caliente"],
  cansancio: ["cansado", "cansada", "agotado", "agotada", "sin energia", "sin energía", "fundido", "fundida", "muerto de sueño", "muerta de sueño"],
  ansiedad: ["ansioso", "ansiosa", "nervioso", "nerviosa", "estresado", "estresada", "preocupado", "preocupada", "estres", "estrés"]
};

function normalizar(t) {
  return ` ${String(t).toLowerCase().replace(/[.,;:!¡¿?()"]/g, " ").replace(/\s+/g, " ")} `;
}

export function detectar(mensaje = "") {
  const t = normalizar(mensaje);
  for (const [emocion, lista] of Object.entries(SENALES)) {
    for (const senal of lista) {
      const i = t.indexOf(` ${senal} `);
      if (i === -1) continue;
      const previo = t.slice(Math.max(0, i - 16), i + 1);
      if (/\b(no|ni|nada)\s+(estoy\s+|me siento\s+|tan\s+)?$/.test(previo)) continue;
      return { emocion: emocion === "cansancio_sueno" ? "cansancio" : emocion, senal };
    }
  }
  return null;
}

function base(userId) {
  return { userId, actual: null, desde: null, evidencia: null, historial: [], actualizado: null };
}

export function obtener(userId) {
  return { ...base(userId), ...storage.readUserData(NAMESPACE, userId, base(userId)) };
}

// Actualiza desde un mensaje del usuario o desde un evento ({ emocion, evidencia, fuente }).
export function registrar(userId, mensajeOEvento, ahora = Date.now()) {
  if (!userId) return null;
  const det = typeof mensajeOEvento === "string"
    ? (d => d && { emocion: d.emocion, evidencia: String(mensajeOEvento).slice(0, 200), fuente: "conversacion" })(detectar(mensajeOEvento))
    : mensajeOEvento;
  const estado = obtener(userId);
  if (!det?.emocion) return estado;
  estado.historial = [...estado.historial, { emocion: det.emocion, evidencia: det.evidencia, fuente: det.fuente || "evento", timestamp: ahora }].slice(-MAX_HISTORIAL);
  if (estado.actual !== det.emocion) estado.desde = ahora;
  estado.actual = det.emocion;
  estado.evidencia = det.evidencia;
  estado.actualizado = ahora;
  storage.writeUserData(NAMESPACE, userId, estado);
  return estado;
}

// Hecho para el contexto del LLM (o null si no hay señal vigente).
export function resumen(userId, ahora = Date.now()) {
  const e = obtener(userId);
  if (!e.actual || !e.actualizado) return null;
  const minutos = Math.round((ahora - e.actualizado) / 60000);
  return {
    emocion: e.actual,
    vigente: ahora - e.actualizado <= VIGENCIA_MS,
    haceMinutos: minutos,
    evidencia: e.evidencia,
    recientes: e.historial.slice(-5).map(h => h.emocion)
  };
}

export default { detectar, obtener, registrar, resumen };
