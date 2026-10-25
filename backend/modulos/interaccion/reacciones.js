// reacciones.js — reacción ocasional del avatar (un emoji sobre la burbuja del USUARIO).
// Decide el orquestador por señales del mensaje (estado emocional detectado, gratitud, logro, humor, gusto nuevo),
// con baja probabilidad y sin reaccionar en turnos seguidos. No genera texto.
import storage from "../../utils/jsonStorage.js";
import { detectar as detectarEmocion } from "../../memoria/estadoEmocional.js";

const NAMESPACE = "reacciones";
// Allowlist curada: cualquier emoji fuera de esta lista (del LLM o de una regla) se descarta → sin reacción.
export const PERMITIDOS = Object.freeze(["❤️", "😂", "😮", "😢", "👍", "😊", "🥰", "🔥", "🙏", "🎉", "🤔", "😅"]);
// Tope de frecuencia: nunca en mensajes seguidos; mínimo MIN_TURNOS mensajes del usuario desde la última reacción
// y probabilidad baja → en promedio ~1 de cada 5-6 mensajes como mucho (y solo si el mensaje lo pide claramente).
export const POLITICA = Object.freeze({ minTurnos: 4, probabilidad: 0.35 });
const RE_PISTA = /\[\[\s*reacci[oó]n\s*:([^\]]*)\]\]/gi;
// Contrato técnico (prompt): uso escaso.
export const LINEA_CONTRATO = `Reacción opcional (usala MUY poco, solo si el mensaje del usuario es claramente emotivo, gracioso, una buena noticia o un agradecimiento): al final podés agregar [[reaccion: <emoji>]] con UNO de ${PERMITIDOS.join(" ")}. En la mayoría de los mensajes NO pongas ninguna.`;

export function validarEmoji(e) {
  const t = String(e ?? "").trim();
  if (!t) return null;
  if (PERMITIDOS.includes(t)) return t;
  const sinVs = t.replace(/\uFE0F/g, "");
  return PERMITIDOS.find(p => p.replace(/\uFE0F/g, "") === sinVs) || null;
}

/** Saca la pista [[reaccion: X]] del texto del LLM. Emoji inválido → null. */
export function parsearPista(respuesta = "") {
  const src = String(respuesta ?? "");
  let emoji = null;
  for (const m of src.matchAll(RE_PISTA)) emoji = emoji || validarEmoji(m[1]);
  return { texto: src.replace(RE_PISTA, "").replace(/[ \t]+\n/g, "\n").trim(), emoji };
}

const POR_EMOCION = { alegria: "😊", tristeza: "😢", cansancio: "😅", ansiedad: "🙏", enojo: "😮", descansado: "😊" };
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
export function decidir(userId, mensaje, { gustosNuevos = [], pistaLLM = null, random = Math.random, probabilidad = Number(process.env.ME2_REACTION_PROB ?? POLITICA.probabilidad) } = {}) {
  const base = candidato(mensaje, { gustosNuevos });
  // El mensaje tiene que pedirlo claramente (señal local); el LLM solo puede elegir QUÉ emoji, no si reacciona.
  const sugerido = validarEmoji(pistaLLM);
  const c = base ? { emoji: validarEmoji(sugerido || base.emoji), motivo: sugerido ? "pista_llm" : base.motivo } : null;
  if (c && !c.emoji) c.emoji = null;
  const estado = userId ? storage.readUserData(NAMESPACE, userId, { turnosDesdeUltima: 99 }) : { turnosDesdeUltima: 99 };
  const turnos = (estado.turnosDesdeUltima ?? 99) + 1;
  // Nunca en turnos seguidos (mínimo 2 mensajes del usuario sin reacción entre medio).
  const toca = Boolean(c?.emoji) && turnos >= POLITICA.minTurnos && random() < Math.min(probabilidad, 0.5);
  if (userId) storage.writeUserData(NAMESPACE, userId, { turnosDesdeUltima: toca ? 0 : turnos, ultima: toca ? { ...c, timestamp: Date.now() } : estado.ultima || null });
  return toca ? c : null;
}

export default { PERMITIDOS, POLITICA, LINEA_CONTRATO, validarEmoji, parsearPista, candidato, decidir };
