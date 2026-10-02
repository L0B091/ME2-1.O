// backupManager.js — Respaldo en la nube (feature Premium).
// El cliente Android envía la copia YA CIFRADA (ownerHash/iv/ciphertext); el backend solo la guarda.
// Almacenamiento detrás de un adaptador: local-json (dev, default) | db (stub para producción).
import crypto from "crypto";
import localJsonAdapter from "./backupAdapters/localJsonAdapter.js";
import dbAdapter from "./backupAdapters/dbAdapter.js";

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

function normalizarBackup(userId, rawBackup = {}) {
  return {
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
  return { userId, hasBackup: true, updatedAt: backup.updatedAt, checksum: backup.checksum, version: backup.version, storage: store.nombre };
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

export default { guardarBackup, obtenerBackup, adaptadorActivo, ADAPTADORES };
