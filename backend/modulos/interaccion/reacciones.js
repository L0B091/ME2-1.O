// reacciones.js — reacción ocasional del avatar (un emoji sobre la burbuja del USUARIO).
// Decide el orquestador por señales del mensaje (estado emocional detectado, gratitud, logro, humor, gusto nuevo),
// con baja probabilidad y sin reaccionar en turnos seguidos. No genera texto.
import storage from "../../utils/jsonStorage.js";
import { detectar as detectarEmocion } from "../../memoria/estadoEmocional.js";

const NAMESPACE = "reacciones";
const POR_EMOCION = { alegria: "😄", tristeza: "🫂", cansancio: "😴", ansiedad: "🫶", enojo: "😮‍💨", descansado: "😌" };
const SENALES = [
  [/\b(gracias|te agradezco|genia|genio|sos lo m[aá]s)(?![a-záéíóúñ])/i, "❤️"],
  [/\b(lo logr[eé]|aprob[eé]|consegu[ií]|me ascendieron|gan[eé]|me recib[ií])(?![a-záéíóúñ])/i, "🎉"],
  [/\b(ja){2,}|\bjsjs|\blol\b|\bxd\b|😂/i, "😂"]
];

export function candidato(mensaje = "", { gustosNuevos = [] } = {}) {
  for (const [re, emoji] of SENALES) if (re.test(mensaje)) return { emoji, motivo: "senal_mensaje" };
  const emo = detectarEmocion(mensaje);
  if (emo && POR_EMOCION[emo.emocion]) return { emoji: POR_EMOCION[emo.emocion], motivo: `emocion:${emo.emocion}` };
  if (gustosNuevos.length) return { emoji: "🔥", motivo: "gusto_nuevo" };
  return null;
}

/** @returns {{emoji:string, motivo:string}|null} */
export function decidir(userId, mensaje, { gustosNuevos = [], random = Math.random, probabilidad = Number(process.env.ME2_REACTION_PROB ?? 0.3) } = {}) {
  const c = candidato(mensaje, { gustosNuevos });
  const estado = userId ? storage.readUserData(NAMESPACE, userId, { turnosDesdeUltima: 99 }) : { turnosDesdeUltima: 99 };
  const turnos = (estado.turnosDesdeUltima ?? 99) + 1;
  // Nunca en turnos seguidos (mínimo 2 mensajes del usuario sin reacción entre medio).
  const toca = c && turnos >= 3 && random() < probabilidad;
  if (userId) storage.writeUserData(NAMESPACE, userId, { turnosDesdeUltima: toca ? 0 : turnos, ultima: toca ? { ...c, timestamp: Date.now() } : estado.ultima || null });
  return toca ? c : null;
}

export default { candidato, decidir };
