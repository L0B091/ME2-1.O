// backupManager.js — Respaldo en la nube (feature Premium).
// El cliente Android envía la copia YA CIFRADA (ownerHash/iv/ciphertext); el backend solo la guarda.
// Almacenamiento detrás de un adaptador: local-json (dev, default) | db (stub para producción).
import crypto from "crypto";
import localJsonAdapter from "./backupAdapters/localJsonAdapter.js";
import dbAdapter from "./backupAdapters/dbAdapter.js";
import relojApi from "../../api/reloj.js";
import memoriaConversacional from "../../memoria/memoriaConversacional.js";
import horaApi from "../../api/hora.js";

const ADAPTADORES = { local: localJsonAdapter, db: dbAdapter };

function adaptador() {
  const clave = String(process.env.BACKUP_STORAGE || "local").trim().toLowerCase();
  return ADAPTADORES[clave] || localJsonAdapter;
}

function adaptadorActivo() {
  return adaptador().nombre;
}

function registroBase(userId) {
  return { userId, backup: null, historial: [] };
}

function checksum(value = "") {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

const num = (v) => (v == null || v === "" || !Number.isFinite(Number(v)) ? null : Number(v));

/**
 * Hilo en tiempo y espacio: cuándo y dónde fue la última interacción antes del respaldo (metadato en claro, solo
 * fecha + ciudad/zona; la memoria en sí viaja cifrada). Lo informa el teléfono; si falta, se completa con lo que
 * el backend ya sabe (reloj de actividad + ubicación registrada en el onboarding).
 */
function normalizarContinuidad(userId, raw = {}) {
  const c = raw && typeof raw === "object" ? raw : {};
  const ubic = (() => { try { return memoriaConversacional.obtener(userId)?.ubicacion || null; } catch { return null; } })();
  const lugar = c.lugar && typeof c.lugar === "object" ? c.lugar : {};
  const atCliente = num(c.ultimaInteraccionAt) ?? (c.ultimaInteraccionAt ? Date.parse(c.ultimaInteraccionAt) : null);
  const atServidor = relojApi.obtenerUltimaInteraccion(userId)?.getTime?.() ?? null;
  const at = Number.isFinite(atCliente) ? atCliente : atServidor;
  return {
    ultimaInteraccionAt: Number.isFinite(at) ? at : null,
    lugar: {
      ciudad: String(lugar.ciudad || ubic?.ciudad || "").trim() || null,
      lat: num(lugar.lat) ?? num(ubic?.lat),
      lon: num(lugar.lon) ?? num(ubic?.lon),
      zonaHoraria: String(lugar.zonaHoraria || ubic?.zonaHoraria || "").trim() || null
    },
    origen: Number.isFinite(atCliente) ? "telefono" : "servidor"
  };
}

function normalizarBackup(userId, rawBackup = {}) {
  return {
    continuidad: normalizarContinuidad(userId, rawBackup.continuidad),
    version: Number(rawBackup.version || 1),
    userId,
    ownerHash: String(rawBackup.ownerHash || ""),
    iv: String(rawBackup.iv || ""),
    ciphertext: String(rawBackup.ciphertext || ""),
    updatedAt: rawBackup.updatedAt || new Date().toISOString(),
    keyVersion: Number(rawBackup.keyVersion || 1),
    checksum: checksum(rawBackup.ciphertext || "")
  };
}

function validarBackup(backup = {}) {
  if (!backup.ownerHash || !backup.iv || !backup.ciphertext) {
    const error = new Error("Respaldo premium inválido");
    error.status = 400;
    throw error;
  }
}

async function guardarBackup(userId, rawBackup = {}) {
  validarBackup(rawBackup);
  const store = adaptador();
  const registro = await store.leer(userId, registroBase(userId));
  const backup = normalizarBackup(userId, rawBackup);
  const evento = { tipo: "backup_actualizado", updatedAt: backup.updatedAt, checksum: backup.checksum };
  await store.escribir(userId, { userId, backup, historial: [...(registro.historial || []), evento].slice(-25) });
  return { userId, hasBackup: true, updatedAt: backup.updatedAt, checksum: backup.checksum, version: backup.version, continuidad: backup.continuidad, storage: store.nombre };
}

async function obtenerBackup(userId) {
  const store = adaptador();
  const registro = await store.leer(userId, registroBase(userId));
  return {
    userId,
    hasBackup: Boolean(registro.backup),
    backup: registro.backup,
    historial: Array.isArray(registro.historial) ? registro.historial : [],
    storage: store.nombre
  };
}

/** Restauración en un teléfono nuevo: devuelve el respaldo y deja pendiente el hilo de continuidad para el próximo chat. */
async function restaurarBackup(userId, { dispositivo = null, ahora = Date.now() } = {}) {
  const store = adaptador();
  const registro = await store.leer(userId, registroBase(userId));
  if (!registro.backup) return { userId, hasBackup: false, backup: null, continuidad: null, storage: store.nombre };
  const restauracion = { restauradoEn: ahora, dispositivo, continuidad: registro.backup.continuidad || null, entregada: false };
  const evento = { tipo: "backup_restaurado", restauradoEn: new Date(ahora).toISOString(), dispositivo };
  await store.escribir(userId, { ...registro, restauracion, historial: [...(registro.historial || []), evento].slice(-25) });
  return { userId, hasBackup: true, backup: registro.backup, continuidad: restauracion.continuidad, storage: store.nombre };
}

function describirLapso(ms) {
  const min = Math.round(ms / 60000);
  if (min < 60) return `${min} minutos`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} horas`;
  return `${Math.round(h / 24)} días`;
}

/**
 * HECHOS de continuidad para el primer chat tras restaurar (una vez). Marca la entrega.
 * @returns {Promise<string[]>}
 */
async function lineasContinuidad(userId, { ahora = Date.now(), marcar = true } = {}) {
  let store, registro;
  try {
    store = adaptador();
    registro = await store.leer(userId, registroBase(userId));
  } catch { return []; }
  const r = registro?.restauracion;
  if (!r || r.entregada) return [];
  const c = r.continuidad || {};
  const zona = c.lugar?.zonaHoraria || horaApi.ZONA_DEFAULT;
  const l = [`Respaldo en la nube (Premium): la memoria del usuario se RESTAURÓ en un teléfono nuevo el ${horaApi.formatearFecha(new Date(r.restauradoEn), zona)}; es la misma relación, continuar donde quedó.`];
  if (Number.isFinite(c.ultimaInteraccionAt)) {
    const h = horaApi.obtenerHoraActual(zona, new Date(c.ultimaInteraccionAt));
    l.push(`Última interacción antes del cambio de teléfono: ${h.fechaLarga}, ${h.hora} (${zona}), hace ${describirLapso(ahora - c.ultimaInteraccionAt)}${c.lugar?.ciudad ? `, cuando el usuario estaba en ${c.lugar.ciudad}` : ""}. Retomar el hilo en tiempo y espacio.`);
  } else if (c.lugar?.ciudad) {
    l.push(`Lugar de la última interacción antes del cambio de teléfono: ${c.lugar.ciudad}.`);
  }
  if (marcar) await store.escribir(userId, { ...registro, restauracion: { ...r, entregada: true, entregadaEn: ahora } });
  return l;
}

export default { guardarBackup, obtenerBackup, restaurarBackup, lineasContinuidad, adaptadorActivo, ADAPTADORES };
