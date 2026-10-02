// continuidad.js — continuidad conversacional como DATOS de memoria.
// Temas pendientes que el usuario mencionó (mañana/después/pendiente/recordame), persistidos por usuario,
// más la función obtenerContinuidad usada por la iniciativa para proponer fuentes con evidencia real.
import crypto from "node:crypto";
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "continuidad";
const MAX = 30;
const PATRON_PENDIENTE = /\b(pendiente|retomemos|recordame|recordarme|manana|despues|luego te cuento|te cuento)\b|mañana|después|no olvides/i;

function base(userId) {
  return { userId, pendientes: [], ultimoTema: null, actualizado: null };
}

export function obtener(userId) {
  return { ...base(userId), ...storage.readUserData(NAMESPACE, userId, base(userId)) };
}

// Registra el mensaje del usuario: guarda último tema y, si corresponde, un pendiente.
export function registrar(userId, mensaje, ahora = Date.now()) {
  if (!userId || !mensaje) return obtener(userId);
  const texto = String(mensaje).trim().slice(0, 300);
  const estado = obtener(userId);
  estado.ultimoTema = { texto, timestamp: ahora };
  if (PATRON_PENDIENTE.test(texto) && !texto.endsWith("?")) {
    estado.pendientes = [...estado.pendientes.filter(p => p.texto !== texto), { texto, timestamp: ahora, resuelto: false }].slice(-MAX);
  }
  estado.actualizado = new Date(ahora).toISOString();
  storage.writeUserData(NAMESPACE, userId, estado);
  return estado;
}

export function pendientesVigentes(userId, ahora = Date.now(), vigenciaMs = 7 * 24 * 3600e3) {
  return obtener(userId).pendientes.filter(p => !p.resuelto && p.timestamp <= ahora && p.timestamp >= ahora - vigenciaMs);
}

// Fuentes de continuidad para la política de iniciativa (misma lógica que antes, sin texto de personaje).
export function obtenerContinuidad(memoria, ahora, vigenciaMs) {
  const reciente = (memoria.recentConversation || []).filter(item =>
    item.timestamp <= ahora && item.timestamp >= ahora - vigenciaMs
  );
  const usuarios = reciente.filter(item => item.role === "user");
  const ultimo = usuarios.at(-1);
  if (!ultimo) return [];
  const referenciaDe = item => `${item.timestamp}:${crypto.createHash("sha256").update(item.text).digest("hex")}`;
  const referencia = referenciaDe(ultimo);
  const pendientes = usuarios.filter(item => PATRON_PENDIENTE.test(item.text));
  const fuentes = [];
  if (pendientes.length || (ultimo.text.includes("?") && reciente.at(-1)?.role === "user")) {
    fuentes.push({
      categoria: "CONVERSACION", motivo: "Retomar un tema pendiente documentado en la conversacion",
      referenciaEvento: referencia, timestamp: ultimo.timestamp,
      contexto: { evidencia: (pendientes.at(-1) || ultimo).text, pendiente: true }
    });
  }
  for (const recuerdo of (memoria.importantMemories || []).slice(-3)) {
    if (recuerdo.text && recuerdo.timestamp <= ahora) fuentes.push({
      categoria: "RECUERDO", motivo: "Retomar un recuerdo importante que el usuario compartio",
      referenciaEvento: referenciaDe(recuerdo), timestamp: ultimo.timestamp,
      contexto: { evidencia: recuerdo.text, referenciaMemoria: recuerdo.timestamp }
    });
  }
  fuentes.push({
    categoria: "CURIOSIDAD", motivo: "Preguntar por un detalle del tema reciente del usuario",
    referenciaEvento: referencia, timestamp: ultimo.timestamp,
    contexto: { evidencia: ultimo.text }
  });
  fuentes.push({
    categoria: "SOCIAL", motivo: "Retomar naturalmente el contacto con contexto real",
    referenciaEvento: referencia, timestamp: ultimo.timestamp,
    contexto: { evidencia: ultimo.text, espontanea: true }
  });
  return fuentes;
}

export default { obtener, registrar, pendientesVigentes, obtenerContinuidad };
