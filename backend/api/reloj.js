// Módulo de reloj y seguimiento de inactividad de usuarios para ME2
import horaAPI from './hora.js';
import storage from '../utils/jsonStorage.js';

const NAMESPACE = "reloj_actividad";
const KEY = "ultima_interaccion";

/** @type {Map<string, {ultimaInteraccion: Date}>} */
const memoriaUsuarios = new Map();

function cargar() {
  const data = storage.readGlobalData(NAMESPACE, KEY, { usuarios: {} });
  const usuarios = data?.usuarios && typeof data.usuarios === "object" ? data.usuarios : {};
  memoriaUsuarios.clear();
  for (const [id, raw] of Object.entries(usuarios)) {
    const ts = raw?.ultimaInteraccion ? new Date(raw.ultimaInteraccion) : null;
    if (ts && !Number.isNaN(ts.getTime())) {
      memoriaUsuarios.set(String(id), { ultimaInteraccion: ts });
    }
  }
}

function persistir() {
  const usuarios = {};
  for (const [id, value] of memoriaUsuarios.entries()) {
    usuarios[id] = {
      ultimaInteraccion: value.ultimaInteraccion?.toISOString?.() || value.ultimaInteraccion
    };
  }
  storage.writeGlobalData(NAMESPACE, KEY, { usuarios });
}

cargar();

function obtenerHoraActual(zonaHoraria = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  return horaAPI.obtenerHoraActual(zonaHoraria);
}

function guardarUltimaInteraccion(usuarioID) {
  const ahora = new Date();
  const userKey = String(usuarioID || "anonimo");
  memoriaUsuarios.set(userKey, {
    ultimaInteraccion: ahora
  });
  persistir();
}

function tiempoDesdeUltimaInteraccion(usuarioID) {
  const usuario = memoriaUsuarios.get(
    String(usuarioID || "anonimo")
  );
  if (!usuario || !usuario.ultimaInteraccion) return null;
  const ahora = new Date();
  const diferenciaMs = ahora - new Date(usuario.ultimaInteraccion);
  return Math.floor(diferenciaMs / 60000);
}

/** Fecha de la última interacción persistida (Date) o null. */
function obtenerUltimaInteraccion(usuarioID) {
  return memoriaUsuarios.get(String(usuarioID || "anonimo"))?.ultimaInteraccion || null;
}

function formatearFecha(fechaObj) {
  return horaAPI.formatearFecha(fechaObj);
}

export default {
  obtenerHoraActual,
  formatearFecha,
  guardarUltimaInteraccion,
  tiempoDesdeUltimaInteraccion,
  obtenerUltimaInteraccion
};
