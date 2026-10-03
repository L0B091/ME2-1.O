import crypto from "crypto";
import usuariosMemoria from "../memoria/usuariosMemoria.js";
import storage from "../utils/jsonStorage.js";

const DURACION_TOKEN = 1000 * 60 * 60 * 24 * 7;
const SESSION_NAMESPACE = "sesiones_auth";
const SESSION_KEY = "tokens";

/**
 * Sesiones indexadas por SHA-256 del token: en memoria y en disco solo queda el hash (B4). Un volcado de
 * data/sesiones_auth no permite suplantar a nadie.
 * @type {Map<string, {tokenHash:string,userId:string,email:string,creado:number,expira:number}>}
 */
const sesiones = new Map();

export function hashToken(token) {
  return crypto.createHash("sha256").update(String(token || "")).digest("hex");
}
const ES_HASH = /^[a-f0-9]{64}$/;

function buscarSesion(token) {
  if (!token || typeof token !== "string" || token.length > 256) return null;
  const h = hashToken(token);
  const sesion = sesiones.get(h);
  if (!sesion) return null;
  // Comparación en tiempo constante del hash guardado (defensa en profundidad).
  const a = Buffer.from(sesion.tokenHash, "hex");
  const b = Buffer.from(h, "hex");
  return a.length === b.length && crypto.timingSafeEqual(a, b) ? sesion : null;
}

function cargarSesiones() {
  const data = storage.readGlobalData(SESSION_NAMESPACE, SESSION_KEY, { tokens: {} });
  const tokens = data?.tokens && typeof data.tokens === "object" ? data.tokens : {};
  const ahora = Date.now();
  sesiones.clear();
  let migradas = false;
  for (const [clave, sesion] of Object.entries(tokens)) {
    if (!sesion || typeof sesion !== "object") continue;
    if (!Number.isFinite(sesion.expira) || sesion.expira <= ahora) continue;
    // Formato viejo: la clave era el token en claro → se migra a su hash.
    const tokenHash = ES_HASH.test(clave) ? clave : hashToken(clave);
    if (tokenHash !== clave || sesion.token) migradas = true;
    sesiones.set(tokenHash, {
      tokenHash,
      userId: String(sesion.userId || ""),
      email: String(sesion.email || ""),
      creado: Number(sesion.creado) || ahora,
      expira: Number(sesion.expira)
    });
  }
  if (migradas) persistirSesiones();
}

function persistirSesiones() {
  const ahora = Date.now();
  const tokens = {};
  for (const [tokenHash, sesion] of sesiones.entries()) {
    if (sesion.expira <= ahora) {
      sesiones.delete(tokenHash);
      continue;
    }
    tokens[tokenHash] = { userId: sesion.userId, email: sesion.email, creado: sesion.creado, expira: sesion.expira };
  }
  storage.writeGlobalData(SESSION_NAMESPACE, SESSION_KEY, { tokens });
}

cargarSesiones();

function generarToken() {
  return crypto.randomBytes(48).toString("hex");
}

function crearHash(password, salt = crypto.randomBytes(16).toString("hex")) {
  const passwordHash = crypto.scryptSync(String(password), salt, 64).toString("hex");
  return { passwordHash, passwordSalt: salt };
}

function emitirSesion(usuario) {
  const token = generarToken();
  const creado = Date.now();
  const expira = creado + DURACION_TOKEN;

  const tokenHash = hashToken(token);
  sesiones.set(tokenHash, {
    tokenHash,
    userId: usuario.id,
    email: usuario.email,
    creado,
    expira
  });
  persistirSesiones();

  return {
    token,
    expiraEn: expira,
    perfil: {
      userId: usuario.id,
      email: usuario.email,
      displayName: usuario.displayName,
      photoUrl: usuario.photoUrl
    }
  };
}

function registrarUsuario(email, password, perfil = {}, tipoLogin = "local") {
  const normalizedEmail = usuariosMemoria.normalizeEmail(email);
  if (!normalizedEmail) {
    return { ok: false, error: "Email requerido" };
  }

  if (!password) {
    return { ok: false, error: "Contraseña requerida" };
  }

  if (usuariosMemoria.obtenerUsuario(normalizedEmail)) {
    return { ok: false, error: "Usuario ya registrado" };
  }

  const { passwordHash, passwordSalt } = crearHash(password);
  const usuario = usuariosMemoria.guardarUsuario(normalizedEmail, {
    email: normalizedEmail,
    passwordHash,
    passwordSalt,
    tipoLogin,
    displayName: perfil.displayName || perfil.nombre || normalizedEmail
  });

  const sesion = emitirSesion(usuario);
  return { ok: true, ...sesion };
}

function loginUsuario(email, password) {
  const normalizedEmail = usuariosMemoria.normalizeEmail(email);
  const usuario = usuariosMemoria.obtenerUsuario(normalizedEmail);

  if (!usuario) {
    return { ok: false, error: "Usuario no encontrado" };
  }

  if (!usuario.passwordHash || !usuario.passwordSalt) {
    return { ok: false, error: "Este usuario debe autenticarse con Google" };
  }

  const { passwordHash } = crearHash(password, usuario.passwordSalt);
  if (passwordHash !== usuario.passwordHash) {
    return { ok: false, error: "Contraseña incorrecta" };
  }

  usuariosMemoria.actualizarUltimoLogin(normalizedEmail);
  return { ok: true, ...emitirSesion(usuario) };
}

function iniciarSesionParaUsuario(email) {
  const usuario = usuariosMemoria.obtenerUsuario(email);
  if (!usuario) {
    return { ok: false, error: "Usuario no encontrado" };
  }

  usuariosMemoria.actualizarUltimoLogin(usuario.email);
  return { ok: true, ...emitirSesion(usuario) };
}

function validarToken(token) {
  const sesion = buscarSesion(token);
  if (!sesion) return null;

  if (Date.now() > sesion.expira) {
    sesiones.delete(sesion.tokenHash);
    persistirSesiones();
    return null;
  }

  const usuario = usuariosMemoria.obtenerUsuario(sesion.email);
  // El token pertenece a un usuario concreto: si el registro del email fue reemplazado (p. ej. una cuenta local
  // previa descartada al vincular Google), los tokens viejos dejan de valer.
  if (!usuario || (sesion.userId && sesion.userId !== usuario.id)) return null;

  return {
    userId: usuario.id,
    email: usuario.email,
    perfil: {
      displayName: usuario.displayName,
      photoUrl: usuario.photoUrl,
      leyenda: usuario.leyenda,
      premiumUntil: usuario.premiumUntil
    },
    expira: sesion.expira
  };
}

function sesionesActivas() {
  const ahora = Date.now();
  return Array.from(sesiones.values())
    .filter(sesion => sesion.expira > ahora)
    .map(sesion => ({
      userId: sesion.userId,
      email: sesion.email,
      expira: new Date(sesion.expira).toISOString()
    }));
}

function cerrarSesion(token) {
  const sesion = buscarSesion(token);
  const existed = sesion ? sesiones.delete(sesion.tokenHash) : false;
  if (existed) persistirSesiones();
  return existed ? { ok: true, mensaje: "Sesión cerrada" } : { ok: false, error: "Token inválido" };
}

/** Login local (email + contraseña): solo desarrollo/pruebas. Producción: Google es el único método. */
export function localAuthEnabled() {
  return String(process.env.LOCAL_AUTH_ENABLED || "").trim().toLowerCase() === "true";
}

export default {
  localAuthEnabled,
  registrarUsuario,
  loginUsuario,
  iniciarSesionParaUsuario,
  validarToken,
  sesionesActivas,
  cerrarSesion
};
