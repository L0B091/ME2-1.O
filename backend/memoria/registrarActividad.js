// backend/memoria/registrarActividad.js
// Registro de actividad del usuario (PERSISTENTE)

import memoriaPersistente from "./memoriaPersistente.js";

const MAX_HORAS = 12;
const CLAVE_ACTIVIDAD = "__actividad_usuario__";
const CLAVE_ACTIVIDAD_LEGACY = "_actividad_usuario_";

/**
 * Estructura base de actividad
 */
function estructuraBase() {
  return {
    horasActivas: [],
    ultimaInteraccion: null
  };
}

/**
 * Normaliza la estructura de actividad.
 */
function normalizarActividad(actividad) {
  if (!actividad || typeof actividad !== "object") {
    return estructuraBase();
  }

  const horas = Array.isArray(actividad.horasActivas)
    ? actividad.horasActivas
        .filter(h => Number.isInteger(h) && h >= 0 && h <= 23)
    : [];

  return {
    horasActivas: [...new Set(horas)]
      .sort((a, b) => a - b)
      .slice(-MAX_HORAS),

    ultimaInteraccion:
      Number.isFinite(actividad.ultimaInteraccion)
        ? actividad.ultimaInteraccion
        : null
  };
}

/**
 * Busca el registro especial de actividad.
 */
function obtenerRegistroActividad(memoria) {
  if (!Array.isArray(memoria)) {
    return null;
  }

  return memoria.find(
    item => item && (item.clave === CLAVE_ACTIVIDAD || item.clave === CLAVE_ACTIVIDAD_LEGACY)
  ) || null;
}

/**
 * Obtener actividad del usuario.
 */
function obtenerActividad(usuarioId) {
  if (!usuarioId) {
    return estructuraBase();
  }

  const memoria = memoriaPersistente.obtener(usuarioId);
  const registro = obtenerRegistroActividad(memoria);

  return normalizarActividad(registro?.actividad);
}

/**
 * Registrar actividad del usuario.
 *
 * La persistencia se actualiza mediante la operación
 * específica de actividad que implementaremos en
 * memoriaPersistente.js.
 */
function registrarActividad(usuarioId, fecha = new Date()) {
  if (!usuarioId || !(fecha instanceof Date) || Number.isNaN(fecha.getTime())) {
    return null;
  }

  const actividad = obtenerActividad(usuarioId);

  const hora = fecha.getHours();

  if (!actividad.horasActivas.includes(hora)) {
    actividad.horasActivas.push(hora);
  }

  actividad.horasActivas = [...new Set(actividad.horasActivas)]
    .sort((a, b) => a - b)
    .slice(-MAX_HORAS);

  actividad.ultimaInteraccion = fecha.getTime();

  memoriaPersistente.actualizarActividad(
    usuarioId,
    actividad
  );

  return actividad;
}

/**
 * Calcula distancia circular entre dos horas.
 *
 * Ejemplo:
 * 23 → 00 = 1 hora
 */
function distanciaEntreHoras(a, b) {
  const diferencia = Math.abs(a - b);
  return Math.min(diferencia, 24 - diferencia);
}

/**
 * Verifica si el usuario está en horario activo.
 */
function esHorarioActivo(usuarioId, margen = 1) {
  if (!usuarioId || !Number.isFinite(margen) || margen < 0) {
    return false;
  }

  const actividad = obtenerActividad(usuarioId);

  if (!actividad.horasActivas.length) {
    return false;
  }

  const horaActual = new Date().getHours();

  return actividad.horasActivas.some(
    hora => distanciaEntreHoras(hora, horaActual) <= margen
  );
}

/**
 * Obtener última interacción.
 */
function obtenerUltimaInteraccion(usuarioId) {
  return obtenerActividad(usuarioId).ultimaInteraccion;
}

export default {
  registrarActividad,
  obtenerActividad,
  esHorarioActivo,
  obtenerUltimaInteraccion
};