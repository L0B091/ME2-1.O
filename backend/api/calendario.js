// calendario.js — eventos del usuario vía adaptador (local hoy, Google Calendar después).
import notificaciones from "./notificaciones.js";
import localAdapter from "./calendarioAdapters/localAdapter.js";
import googleAdapter from "./calendarioAdapters/googleCalendarAdapter.js";
import storage from "../utils/jsonStorage.js";

const OFFSET = process.env.ME2_TZ_OFFSET || "-03:00";

function leerLocal(userId) {
  return storage.readUserData("calendario", userId, []);
}

function instante(evento) {
  const t = Date.parse(`${evento.fecha}T${(evento.hora || "00:00").padStart(5, "0")}:00${OFFSET}`);
  return Number.isFinite(t) ? t : NaN;
}

function listarEventos(usuarioID) {
  return leerLocal(usuarioID);
}

function agregarEvento(usuarioID, evento) {
  if (!usuarioID || !evento?.fecha || !/^\d{4}-\d{2}-\d{2}$/.test(String(evento.fecha))) {
    return { exito: false, mensaje: "Faltan datos del evento (fecha YYYY-MM-DD)" };
  }
  const hora = /^\d{1,2}:\d{2}$/.test(String(evento.hora || "")) ? String(evento.hora).padStart(5, "0") : "08:00";
  const eventos = leerLocal(usuarioID);
  const nuevoEvento = {
    id: String(evento.id || `${usuarioID}_${Date.now()}`),
    tipo: String(evento.tipo || "evento"),
    fecha: String(evento.fecha),
    hora,
    descripcion: String(evento.descripcion || "Sin descripción"),
    origen: "local",
    createdAt: new Date().toISOString()
  };
  eventos.push(nuevoEvento);
  storage.writeUserData("calendario", usuarioID, eventos);
  const t = instante(nuevoEvento);
  if (Number.isFinite(t)) notificaciones.notificarRecordatorio(usuarioID, nuevoEvento.descripcion, new Date(t).toISOString());
  return { exito: true, mensaje: "Evento agregado correctamente", evento: nuevoEvento };
}

function eliminarEvento(usuarioID, eventoId) {
  const eventos = leerLocal(usuarioID);
  const filtrados = eventos.filter(item => item.id !== eventoId && item.descripcion !== eventoId);
  storage.writeUserData("calendario", usuarioID, filtrados);
  return {
    exito: filtrados.length !== eventos.length,
    mensaje: filtrados.length !== eventos.length ? "Evento eliminado" : "No se encontró el evento"
  };
}

function obtenerEventosProximos(usuarioID, ahora = Date.now()) {
  return leerLocal(usuarioID)
    .filter(evento => instante(evento) >= ahora)
    .sort((a, b) => instante(a) - instante(b));
}

// Une local + Google (si está configurado). Nunca lanza: informa disponibilidad.
async function proximosUnificados(usuarioID, limite = 5) {
  const locales = obtenerEventosProximos(usuarioID);
  let google = [];
  let googleEstado = googleAdapter.disponible() ? "ok" : "no_configurado";
  if (googleAdapter.disponible()) {
    try { google = await googleAdapter.listar(usuarioID); } catch (e) { googleEstado = "error"; }
  }
  return { eventos: [...locales, ...google].sort((a, b) => instante(a) - instante(b)).slice(0, limite), googleCalendar: googleEstado };
}

function calendarioJoi(usuarioID, input) {
  if (!input?.accion) return { exito: false, mensaje: "Acción no definida" };
  if (input.accion === "agregar") return agregarEvento(usuarioID, input.datos);
  if (input.accion === "eliminar") return eliminarEvento(usuarioID, input.datos?.id || input.datos?.descripcion);
  if (input.accion === "obtener") return obtenerEventosProximos(usuarioID);
  return { exito: false, mensaje: "Acción desconocida" };
}

export default {
  listarEventos,
  agregarEvento,
  eliminarEvento,
  obtenerEventosProximos,
  proximosUnificados,
  calendarioJoi,
  adapters: { local: localAdapter, google: googleAdapter }
};
