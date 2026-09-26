// memoria/usuarioMemoria.js
// Estado de personalidad por usuario — durable under backend/data/
import storage from "../utils/jsonStorage.js";

const NAMESPACE = "usuario_memoria_runtime";
const usuariosMemoria = new Map();

function normalizarUsuarioId(usuarioId) {
  return String(usuarioId || "anonimo");
}

function estadoBase() {
  return {
    memoriaCorta: {},
    memoriaPersistente: {},
    recuerdosImportantes: {},
    historialConversacion: [],
    ultimaInteraccion: null,
    ultimoMicro: null,
    ultimoMicroReaccion: null,
    estadoEmocionalActual: "neutral",
    historialEmocional: [],
    preferenciasComunicacion: {
      longitudMensajes: "media",
      estilo: "neutral",
      usaPreguntas: false
    }
  };
}

function persistir(id) {
  const data = usuariosMemoria.get(id);
  if (!data) return;
  storage.writeUserData(NAMESPACE, id, data);
}

function cargar(id) {
  if (usuariosMemoria.has(id)) return;
  const data = storage.readUserData(NAMESPACE, id, null);
  if (data && typeof data === "object" && !Array.isArray(data)) {
    usuariosMemoria.set(id, { ...estadoBase(), ...data });
  }
}

// =========================
// CREAR USUARIO
// =========================
export function registrarUsuario(usuarioId) {
  const id = normalizarUsuarioId(usuarioId);
  cargar(id);
  if (!usuariosMemoria.has(id)) {
    usuariosMemoria.set(id, estadoBase());
    persistir(id);
  }
}

// =========================
// OBTENER USUARIO
// =========================
export function obtenerUsuario(usuarioId) {
  const id = normalizarUsuarioId(usuarioId);
  cargar(id);
  return usuariosMemoria.get(id) ?? null;
}

// =========================
// AGREGAR MENSAJE AL HISTORIAL
// =========================
export function agregarMensaje(usuarioId, mensaje) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);

  usuariosMemoria.get(id).historialConversacion.push({
    mensaje,
    timestamp: Date.now()
  });

  if (usuariosMemoria.get(id).historialConversacion.length > 50) {
    usuariosMemoria.get(id).historialConversacion.shift();
  }

  usuariosMemoria.get(id).ultimaInteraccion = Date.now();
  persistir(id);
}

export function listarUsuarios() {
  return Array.from(usuariosMemoria.keys());
}

export function limpiarUsuario(usuarioId) {
  const id = normalizarUsuarioId(usuarioId);
  cargar(id);
  const usuario = usuariosMemoria.get(id);
  if (usuario) {
    Object.assign(usuario, estadoBase());
    persistir(id);
  }
}

export function guardarUltimoMicro(usuarioId, valor) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  usuariosMemoria.get(id).ultimoMicro = valor;
  persistir(id);
  return valor;
}

export function obtenerUltimoMicro(usuarioId) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  return usuariosMemoria.get(id).ultimoMicro ?? null;
}

export function guardarUltimoMicroReaccion(usuarioId, valor) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  usuariosMemoria.get(id).ultimoMicroReaccion = valor;
  persistir(id);
  return valor;
}

export function obtenerUltimoMicroReaccion(usuarioId) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  return usuariosMemoria.get(id).ultimoMicroReaccion ?? null;
}

export function actualizarEstadoEmocional(usuarioId, estado) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  const usuario = usuariosMemoria.get(id);
  usuario.estadoEmocionalActual = estado || "neutral";
  usuario.historialEmocional.push({
    estado: usuario.estadoEmocionalActual,
    timestamp: Date.now()
  });
  usuario.historialEmocional =
    usuario.historialEmocional.slice(-20);
  persistir(id);
  return usuario.estadoEmocionalActual;
}

export function actualizarPreferenciasComunicacion(
  usuarioId,
  preferencias = {}
) {
  const id = normalizarUsuarioId(usuarioId);
  registrarUsuario(id);
  const usuario = usuariosMemoria.get(id);
  usuario.preferenciasComunicacion = {
    ...usuario.preferenciasComunicacion,
    ...preferencias
  };
  persistir(id);
  return usuario.preferenciasComunicacion;
}
