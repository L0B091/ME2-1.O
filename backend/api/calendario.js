// calendario.js — eventos del usuario vía adaptador (local hoy, Google Calendar después).
import notificaciones from "./notificaciones.js";
import localAdapter from "./calendarioAdapters/localAdapter.js";
import googleAdapter from "./calendarioAdapters/googleCalendarAdapter.js";
import storage from "../utils/jsonStorage.js";
import guardia from "../seguridad/guardiaInstrucciones.js";
import crypto from "crypto";

const OFFSET = process.env.ME2_TZ_OFFSET || "-03:00";

function leerLocal(userId) {
  return storage.readUserData("calendario", userId, []);
}

function instante(evento) {
  const t = Date.parse(`${evento.fecha}T${(evento.hora || "00:00").padStart(5, "0")}:00${OFFSET}`);
  return Number.isFinite(t) ? t : NaN;
}

function finEvento(evento) {
  if (!evento?.fin || !/^\d{2}:\d{2}$/.test(evento.fin)) return instante(evento);
  const t = instante({ ...evento, hora: evento.fin });
  return t >= instante(evento) ? t : instante(evento);
}

export function nuevoIdEvento() {
  return `ev-${Date.now().toString(36)}-${crypto.randomBytes(3).toString("hex")}`;
}

const MAX_EVENTOS_TELEFONO = 100;
/**
 * Calendario propio del teléfono (fuente de verdad en Android), recibido como dato en memoriaLocal.calendario.
 * Se valida y sanea (nunca son instrucciones): id, fecha YYYY-MM-DD, hora HH:mm, fin opcional, descripción, notas.
 */
export function normalizarEventosTelefono(lista) {
  if (!Array.isArray(lista)) return null;
  const vistos = new Set();
  const out = [];
  for (const e of lista.slice(-MAX_EVENTOS_TELEFONO * 2)) {
    if (!e || typeof e !== "object") continue;
    const id = typeof e.id === "string" && /^[A-Za-z0-9_.:-]{1,80}$/.test(e.id) ? e.id : null;
    const fecha = typeof e.fecha === "string" && /^\d{4}-\d{2}-\d{2}$/.test(e.fecha) ? e.fecha : null;
    const hora = typeof e.hora === "string" && /^\d{2}:\d{2}$/.test(e.hora) ? e.hora : null;
    if (!id || !fecha || !hora || vistos.has(id)) continue;
    vistos.add(id);
    const fin = typeof e.fin === "string" && /^\d{2}:\d{2}$/.test(e.fin) ? e.fin : null;
    out.push({
      id, fecha, hora, fin,
      descripcion: guardia.datoDeUsuario(e.descripcion || "", 200) || "Evento",
      notas: e.notas ? guardia.datoDeUsuario(e.notas, 200) || null : null,
      creadoPor: e.creadoPor === "chat" ? "chat" : String(e.creadoPor || "chat").slice(0, 20),
      creadoEn: typeof e.creadoEn === "string" ? e.creadoEn.slice(0, 40) : null,
      origen: "telefono"
    });
  }
  return out.slice(-MAX_EVENTOS_TELEFONO);
}

/**
 * Agenda vigente para un cliente con calendario en el teléfono: eventos del teléfono + los que hubieran quedado en
 * el servidor de versiones anteriores (solo lectura/borrado), sin duplicados, ordenados por fecha. Vigente = aún no
 * terminó (fin, o inicio si no tiene fin).
 */
export function agendaCombinada(usuarioID, eventosTelefono = [], { ahora = Date.now(), limite = 50 } = {}) {
  const servidor = usuarioID && usuarioID !== "anonimo" ? leerLocal(usuarioID).map(e => ({ ...e, origen: "servidor" })) : [];
  const vistos = new Set();
  return [...(eventosTelefono || []), ...servidor]
    .filter(e => { if (!e?.id || vistos.has(e.id)) return false; vistos.add(e.id); return true; })
    .filter(e => Number.isFinite(instante(e)) && finEvento(e) >= ahora)
    .sort((a, b) => instante(a) - instante(b))
    .slice(0, limite);
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
    fin: /^\d{2}:\d{2}$/.test(String(evento.fin || "")) ? String(evento.fin) : null,
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

function calendarioAccion(usuarioID, input) {
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
  calendarioAccion,
  normalizarEventosTelefono,
  agendaCombinada,
  nuevoIdEvento,
  instante,
  adapters: { local: localAdapter, google: googleAdapter }
};
