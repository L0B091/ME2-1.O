// historialIniciativas.js — fuentes de iniciativa usadas por usuario (DATOS de memoria).
// Sirve para rotar fuentes (no repetir la misma dos veces seguidas) y aplicar cooldowns por fuente.
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "iniciativas_fuentes";
const MAX = 30;

function base(userId) {
  return { userId, usadas: [] };
}

export function obtener(userId) {
  return { ...base(userId), ...storage.readUserData(NAMESPACE, userId, base(userId)) };
}

export function registrar(userId, { fuente, referencia = null }, ahora = Date.now()) {
  if (!userId || !fuente) return obtener(userId);
  const estado = obtener(userId);
  estado.usadas = [...estado.usadas, { fuente, referencia, timestamp: ahora }].slice(-MAX);
  storage.writeUserData(NAMESPACE, userId, estado);
  return estado;
}

export function ultimas(userId, n = 3) {
  return obtener(userId).usadas.slice(-n).reverse();
}

export function ultimoUso(userId, fuente) {
  const u = obtener(userId).usadas.filter(x => x.fuente === fuente).at(-1);
  return u ? u.timestamp : null;
}

export function referenciasUsadas(userId, fuente) {
  return new Set(obtener(userId).usadas.filter(x => x.fuente === fuente && x.referencia).map(x => x.referencia));
}

export default { obtener, registrar, ultimas, ultimoUso, referenciasUsadas };
